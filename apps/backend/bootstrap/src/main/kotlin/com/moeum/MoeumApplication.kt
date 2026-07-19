package com.moeum

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class MoeumApplication

fun main(args: Array<String>) {
    runApplication<MoeumApplication>(*args)
}
