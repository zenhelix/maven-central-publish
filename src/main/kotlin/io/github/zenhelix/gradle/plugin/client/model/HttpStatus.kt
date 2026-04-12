package io.github.zenhelix.gradle.plugin.client.model

@JvmInline
public value class HttpStatus(public val code: Int) {
    public override fun toString(): String = code.toString()

    public companion object {
        public val OK: HttpStatus = HttpStatus(200)
        public val CREATED: HttpStatus = HttpStatus(201)
        public val NO_CONTENT: HttpStatus = HttpStatus(204)
        public val BAD_REQUEST: HttpStatus = HttpStatus(400)
        public val TOO_MANY_REQUESTS: HttpStatus = HttpStatus(429)
    }
}
