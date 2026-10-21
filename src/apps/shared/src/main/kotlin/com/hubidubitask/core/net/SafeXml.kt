package com.hubidubitask.core.net

import org.w3c.dom.Document
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/**
 * XML from someone else (a feed, an export file), parsed so it can't reach
 * the user's files or the network: a document that declares a DOCTYPE or an
 * ENTITY is refused before parsing (no XXE, no entity expansion), and the
 * parser has DOCTYPEs, entities and XInclude off wherever the platform lets
 * it. The JDK takes every setting; Android's parser throws on some, and there
 * the refusal is what keeps a DOCTYPE out.
 */
object SafeXml {
    private val HOSTILE = Regex("(?i)<!(DOCTYPE|ENTITY)")

    /** Thrown for a document that declares a DOCTYPE or ENTITY; nothing of it was parsed. */
    class Hostile : java.io.IOException("the document declares a DOCTYPE or ENTITY")

    /** [xml] as a document. Throws [Hostile] before parsing, or SAXException when it isn't XML. */
    fun parse(xml: String): Document {
        if (HOSTILE.containsMatchIn(xml)) throw Hostile()
        val body = xml.trimStart('﻿', ' ', '\t', '\r', '\n')
        return builder().parse(InputSource(StringReader(body)))
    }

    /** The parser, hardened as far as the platform allows. */
    fun builder(): DocumentBuilder = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        whereSupported { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        whereSupported { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        whereSupported { isXIncludeAware = false }
    }.newDocumentBuilder()

    private inline fun whereSupported(set: () -> Unit) {
        try { set() } catch (_: ParserConfigurationException) {} catch (_: UnsupportedOperationException) {}
    }
}
