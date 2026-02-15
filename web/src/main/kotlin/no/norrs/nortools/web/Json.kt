package no.norrs.nortools.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.gson.Gson
import io.javalin.http.Context

private class JsonFacade {
    private val gson = Gson()
    private val tree = ObjectMapper()

    fun writeValueAsString(value: Any?): String = gson.toJson(value)

    fun readTree(content: String) = tree.readTree(content)
}

private val json by lazy { JsonFacade() }

fun jsonString(value: Any?): String = json.writeValueAsString(value)

fun jsonReadTree(content: String) = json.readTree(content)

fun Context.jsonResult(value: Any?) {
    result(jsonString(value))
    contentType("application/json")
}
