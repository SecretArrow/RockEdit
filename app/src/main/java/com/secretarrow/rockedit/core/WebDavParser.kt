package com.secretarrow.rockedit.core

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Minimal WebDAV PROPFIND (Depth: 1) response parser. Pure JVM via
 * XmlPullParser (Android ships kxml; JVM tests use kxml2).
 */
object WebDavParser {

    data class Resource(
        val href: String,
        val isCollection: Boolean,
        val size: Long,
        val lastModified: Long
    )

    /**
     * Parses a 207 Multi-Status XML document into resources. The parent
     * collection itself (first response whose href equals the request path,
     * i.e. no filter) is included; the caller filters it out.
     */
    fun parsePropfind(xml: String): List<Resource> {
        val resources = ArrayList<Resource>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(xml.reader())

            var href: String? = null
            var isCollection = false
            var size = -1L
            var lastModified = 0L

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (local(parser.name)) {
                            "href" -> href = try {
                                parser.nextText().trim()
                            } catch (_: Exception) {
                                null
                            }
                            "collection" -> isCollection = true
                            "getcontentlength" -> size = readLong(parser)
                            "getlastmodified" -> lastModified = readDate(parser) ?: 0L
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (local(parser.name) == "response") {
                            val h = href
                            if (!h.isNullOrEmpty()) {
                                resources.add(Resource(h, isCollection, size, lastModified))
                            }
                            href = null
                            isCollection = false
                            size = -1L
                            lastModified = 0L
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
            // Malformed input: return whatever was collected so far.
        }
        return resources
    }

    /**
     * Converts an href to a decoded path ("dav/folder/" -> "/dav/folder").
     * Keeps a trailing slash distinction out of the result.
     */
    fun hrefToPath(href: String): String {
        val withoutScheme = href.substringAfter("://", href)
        val path = "/" + withoutScheme.substringAfter('/', "")
        val decoded = java.net.URLDecoder.decode(path, "UTF-8")
        return RemotePath.normalize(decoded)
    }

    /** Extracts the display name from a path. */
    fun nameFromPath(path: String): String = RemotePath.name(path)

    private fun local(name: String): String = name.substringAfter(':').lowercase()

    private fun readLong(parser: XmlPullParser): Long {
        return try {
            val text = parser.nextText().trim()
            text.toLongOrNull() ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private fun readDate(parser: XmlPullParser): Long? {
        return try {
            val text = parser.nextText().trim()
            // RFC 1123: "Tue, 01 Jan 2024 12:00:00 GMT"
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .parse(text, java.time.temporal.TemporalQueries.zonedDateTime())
                ?.toInstant()?.toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
}
