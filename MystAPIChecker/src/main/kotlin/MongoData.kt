package org.example

object MongoData {
    val MONGO_URL: String by lazy {
        System.getenv("MONGO_URL")
    }

    val MONGO_PASSWORD by lazy {
        System.getenv("MONGO_PASSWORD")?: ""
    }

    val MONGO_USERNAME by lazy {
        System.getenv("MONGO_USERNAME")?: ""
    }

    val MONGO_DATABASE by lazy {
        System.getenv("MONGO_DATABASE")?: ""
    }
}