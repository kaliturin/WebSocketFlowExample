package com.example.websocketflowexample.utils

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import timber.log.Timber
import kotlin.reflect.KClass

object JsonUtils {

    private val mapper: ObjectMapper = JsonMapper.builder()
        .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
        .build()
        .registerKotlinModule()

    fun <T : Any> fromJson(json: String?, clazz: KClass<T>): T? = fromJson(json, clazz.java)

    fun <T : Any> fromJsonSafe(json: String?, clazz: KClass<T>): T? = fromJsonSafe(json, clazz.java)

    /**
     * Serializes the passed json string to an object of the passed Class
     */
    fun <T> fromJson(json: String?, clazz: Class<T>): T? {
        return if (!json.isNullOrEmpty()) {
            mapper.readerFor(clazz).readValue(json)
        } else null
    }

    /**
     * Serializes the passed json string to an object of the passed Class.
     * Suppresses all the exceptions on failure.
     */
    fun <T> fromJsonSafe(json: String?, clazz: Class<T>): T? {
        return try {
            fromJson(json, clazz)
        } catch (e: Exception) {
            Timber.e(e)
            null
        }
    }

    /**
     * Serializes the passed object to a json string
     */
    fun toJson(obj: Any?): String? {
        return if (obj != null) {
            mapper.writer().withDefaultPrettyPrinter().writeValueAsString(obj)
        } else null
    }

    /**
     * Serializes the passed object to a json string
     * Suppresses all the exceptions on failure.
     */
    fun toJsonSafe(obj: Any?): String? {
        return try {
            toJson(obj)
        } catch (e: Exception) {
            Timber.e(e)
            null
        }
    }
}