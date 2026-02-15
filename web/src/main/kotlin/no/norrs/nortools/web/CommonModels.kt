package no.norrs.nortools.web

data class ErrorResponse(
    val error: String,
)

data class CheckSummary(
    val pass: Int,
    val warn: Int,
    val fail: Int,
    val info: Int,
    val total: Int? = null,
)
