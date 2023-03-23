package com.example.websocketflowexample.websocket.impl

import com.example.websocketflowexample.utils.JsonUtils
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue

/**
 * Socket response dataset example
 */
@Suppress("unused")
@JsonInclude(JsonInclude.Include.NON_NULL)
class SocketResponse(
    val header: Header? = null,
    val body: Body? = null,
    val error: Throwable? = null
) {

    class Header(
        val type: Type = Type.UNKNOWN,
        val time: Long = System.currentTimeMillis()
    ) {
        @JsonFormat(shape = JsonFormat.Shape.OBJECT)
        enum class Type(private val key: String) {
            UNKNOWN("Unknown"),
            INT_DATA("IntData"),
            STRING_DATA("StringData");

            @JsonValue
            override fun toString() = key

            companion object {
                @JsonCreator
                @JvmStatic
                fun fromString(key: String?) = values().find { it.key == key } ?: UNKNOWN
            }
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    class Body(
        val intData: List<Int>? = null,
        val strData: List<String>? = null
    )

    override fun toString(): String {
        return JsonUtils.toJsonSafe(this) ?: super.toString()
    }
}