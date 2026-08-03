package com.chessecho

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ChessEchoApplication

fun main(args: Array<String>) {
    runApplication<ChessEchoApplication>(*args)
}
