package net.yacy.cora.util;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;

public final class SecureXML {

    private SecureXML() {
    }

    public static SAXParserFactory newSAXParserFactory()
            throws ParserConfigurationException,
                   SAXNotRecognizedException,
                   SAXNotSupportedException {

        final SAXParserFactory factory = SAXParserFactory.newInstance();

        factory.setFeature(
            "http://apache.org/xml/features/disallow-doctype-decl",
            true
        );
        factory.setFeature(
            "http://xml.org/sax/features/external-general-entities",
            false
        );
        factory.setFeature(
            "http://xml.org/sax/features/external-parameter-entities",
            false
        );
        factory.setFeature(
            XMLConstants.FEATURE_SECURE_PROCESSING,
            true
        );

        factory.setXIncludeAware(false);

        return factory;
    }
}
