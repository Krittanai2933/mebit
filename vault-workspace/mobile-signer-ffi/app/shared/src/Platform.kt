package com.mebit.app

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
