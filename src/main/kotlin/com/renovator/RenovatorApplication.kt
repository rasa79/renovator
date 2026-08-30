package com.renovator

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class RenovatorApplication

fun main(args: Array<String>) {
    runApplication<RenovatorApplication>(*args)
}
