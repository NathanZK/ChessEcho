package com.chessecho.config

import com.chessecho.domain.TimeControl
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

@Component
class StringToTimeControlConverter : Converter<String, TimeControl> {
    override fun convert(source: String): TimeControl {
        return TimeControl.fromExternal(source)
            ?: throw IllegalArgumentException("Invalid timeControl '$source'. Supported timeControls: RAPID, BLITZ, BULLET, CLASSICAL.")
    }
}
