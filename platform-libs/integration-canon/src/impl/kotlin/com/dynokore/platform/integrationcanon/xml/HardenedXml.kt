package com.dynokore.platform.integrationcanon.xml

import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParserFactory
import javax.xml.stream.XMLInputFactory
import javax.xml.transform.TransformerFactory

/**
 * The one place that builds XML factories (CS-25; INV-Q-7068): a document type declaration is refused, external
 * entities and external DTD, schema and stylesheet access are off, and secure processing is on. Every function
 * returns a new factory; the caller owns it. Mechanism only: no rule set or specification name lives here.
 */
object HardenedXml {
    private const val DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl"

    /** A DOM factory that refuses a document type declaration and neither expands entity references nor includes. */
    fun documentBuilderFactory(): DocumentBuilderFactory {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature(DISALLOW_DOCTYPE, true)
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        return factory
    }

    /** A SAX factory that refuses a document type declaration and does not include. */
    fun saxParserFactory(): SAXParserFactory {
        val factory = SAXParserFactory.newInstance()
        factory.setFeature(DISALLOW_DOCTYPE, true)
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.isXIncludeAware = false
        return factory
    }

    /** A StAX factory with DTD support and external entities off. */
    fun xmlInputFactory(): XMLInputFactory {
        val factory = XMLInputFactory.newFactory()
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        return factory
    }

    /**
     * A transformer factory with secure processing on and no external DTD, schema or stylesheet access. It is the JDK
     * factory whichever JAXP provider is on the classpath (INV-Q-7620).
     */
    fun transformerFactory(): TransformerFactory {
        val factory = TransformerFactory.newDefaultInstance()
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
        return factory
    }
}
