package org.example

object APIData {

    val LICENSE: String by lazy {
        System.getenv("VPN_LICENSE")
    }
}