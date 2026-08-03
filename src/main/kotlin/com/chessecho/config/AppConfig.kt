package com.chessecho.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.web.client.RestClient

@Configuration
@EnableAsync
class AppConfig {
    @Bean
    fun restClient(): RestClient = RestClient.create()
}
