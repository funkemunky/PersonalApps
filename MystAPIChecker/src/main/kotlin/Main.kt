package org.example

import com.mongodb.*
import com.mongodb.client.MongoClients
import com.mongodb.client.model.*
import okhttp3.OkHttpClient
import org.bson.Document
import org.json.JSONObject
import java.util.LinkedList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

fun ipToLong(ipAddress: String): Long {
    var result: Long = 0
    val atoms = ipAddress.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
    for (i in 3 downTo 0) {
        result = result or (atoms[3 - i].toLong() shl i * 8)
    }
    return result and 0xFFFFFFFFL
}

fun main() {
    val connectionString =
        ConnectionString(MongoData.MONGO_URL?:"")
    val settings = MongoClientSettings.builder()
        .applyConnectionString(connectionString)
        .credential(
            MongoCredential.createCredential(
                MongoData.MONGO_USERNAME, MongoData.MONGO_DATABASE,
                MongoData.MONGO_PASSWORD.toCharArray()
            )
        )
        .readPreference(ReadPreference.nearest())
        .build()
    val internal = MongoClients.create(settings)
    val mongoDatabase = internal.getDatabase(MongoData.MONGO_DATABASE)

    val blockedIps = mongoDatabase.getCollection("blockedIps")

    val client = OkHttpClient.Builder().build()

    val executor = Executors.newVirtualThreadPerTaskExecutor()
    val schedular = Executors.newSingleThreadScheduledExecutor()

    val ipsToCheck = LinkedList<String>();

    val updates = LinkedBlockingQueue<WriteModel<Document>>()

    blockedIps.find(Filters.and(Filters.eq("reason", "Mysterium"), Filters.eq("org", "unknown"))).sort(Sorts.descending("ipDecimal")).forEach {
        val ip = it.getString("ip")

        ipsToCheck.add(ip);
    }

    var bulkWrites = 0;
    var lastUpdate = 0L


    var updated = 0;
    var enteredForUpdate = 0

    schedular.scheduleAtFixedRate({
        if(bulkWrites > 2) {
            return@scheduleAtFixedRate
        }
        executor.execute {
            if(updates.size < 500 && System.currentTimeMillis() - lastUpdate < 5000L) {
                return@execute
            }

            if(updates.size == 0) {
                return@execute;
            }
            lastUpdate = System.currentTimeMillis()
            println("Running updater ${updates.size}")

            val toUpdate = ArrayList<WriteModel<Document>>()

            var updating = 0
            while(++updating < 800 && updates.isNotEmpty()) {
                toUpdate.add(updates.poll())
            }

            val options = BulkWriteOptions().ordered(false)

            if(toUpdate.size > 0) {
                bulkWrites++
                val result = blockedIps.bulkWrite(toUpdate, options)

                val changed: Int = result.matchedCount;

                println("Bulk Updated ${changed}/$updating documents.")
                bulkWrites--
                updated+= toUpdate.size
                enteredForUpdate-= toUpdate.size
            }
        }
    }, 1500, 750, TimeUnit.MILLISECONDS);

    var wentForUpdate = 0

    while(ipsToCheck.isNotEmpty()) {
        val ip = ipsToCheck.pop()

        while(bulkWrites > 2 || updates.size > 1000) {
            Thread.sleep(5)
        }

        enteredForUpdate++

        executor.execute {
            val request = okhttp3.Request.Builder()
                .url("https://funkemunky.cc/vpn?license=${APIData.LICENSE}&ip=${ip}")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.code == 200) {
                val json = JSONObject(response.body?.string())

                val org = json.getString("isp")

                println("$ip - $org: $updated updated, ${updates.size} updating...");

                // Create an update model
                val filter = Filters.eq("ipDecimal", ipToLong(ip))
                val update = Updates.set("org", org)
                val updateModel = UpdateOneModel<Document>(filter, update)

                // Add the update model to the list
                synchronized(updates) {
                    updates.add(updateModel)
                }
            } else {
                println("$ip - Error: ${response.code}: " + response.body!!);
            }
            response.close()
        };

        Thread.sleep(10)
    }

    var lastBroadcast = System.currentTimeMillis()
    while(enteredForUpdate > 1) {
        if(System.currentTimeMillis() - lastBroadcast > 1500L) {
            println("Waiting for ${enteredForUpdate} updates to finish...")
            lastBroadcast = System.currentTimeMillis()
        }
    }

    println("Completed scan! Updated $updated ips.")
    println("Shutting down...")

    schedular.shutdown()
    executor.shutdown()
    internal.close()
}