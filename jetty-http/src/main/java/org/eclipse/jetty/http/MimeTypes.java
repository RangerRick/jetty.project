//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MIME Type enum and utilities
 */
public class MimeTypes
{

    private static final Logger LOG = LoggerFactory.getLogger(MimeTypes.class);
    private static final Map<String, String> __dftMimeMap = new HashMap<String, String>();
    private static final Map<String, String> __inferredEncodings = new HashMap<String, String>();
    private static final Map<String, String> __assumedEncodings = new HashMap<String, String>();

    public enum Type
    {
        FORM_ENCODED("application/x-www-form-urlencoded"),
        MESSAGE_HTTP("message/http"),
        MULTIPART_BYTERANGES("multipart/byteranges"),
        MULTIPART_FORM_DATA("multipart/form-data"),

        TEXT_HTML("text/html"),
        TEXT_PLAIN("text/plain"),
        TEXT_XML("text/xml"),
        TEXT_JSON("text/json", StandardCharsets.UTF_8),
        APPLICATION_JSON("application/json", StandardCharsets.UTF_8),

        TEXT_HTML_8859_1("text/html;charset=iso-8859-1", TEXT_HTML),
        TEXT_HTML_UTF_8("text/html;charset=utf-8", TEXT_HTML),

        TEXT_PLAIN_8859_1("text/plain;charset=iso-8859-1", TEXT_PLAIN),
        TEXT_PLAIN_UTF_8("text/plain;charset=utf-8", TEXT_PLAIN),

        TEXT_XML_8859_1("text/xml;charset=iso-8859-1", TEXT_XML),
        TEXT_XML_UTF_8("text/xml;charset=utf-8", TEXT_XML),

        TEXT_JSON_8859_1("text/json;charset=iso-8859-1", TEXT_JSON),
        TEXT_JSON_UTF_8("text/json;charset=utf-8", TEXT_JSON),

        APPLICATION_JSON_8859_1("application/json;charset=iso-8859-1", APPLICATION_JSON),
        APPLICATION_JSON_UTF_8("application/json;charset=utf-8", APPLICATION_JSON);

        private final String _string;
        private final Type _base;
        private final ByteBuffer _buffer;
        private final Charset _charset;
        private final String _charsetString;
        private final boolean _assumedCharset;
        private final HttpField _field;

        Type(String s)
        {
            _string = s;
            _buffer = BufferUtil.toBuffer(s);
            _base = this;
            _charset = null;
            _charsetString = null;
            _assumedCharset = false;
            _field = new PreEncodedHttpField(HttpHeader.CONTENT_TYPE, _string);
        }

        Type(String s, Type base)
        {
            _string = s;
            _buffer = BufferUtil.toBuffer(s);
            _base = base;
            int i = s.indexOf(";charset=");
            _charset = Charset.forName(s.substring(i + 9));
            _charsetString = _charset.toString().toLowerCase(Locale.ENGLISH);
            _assumedCharset = false;
            _field = new PreEncodedHttpField(HttpHeader.CONTENT_TYPE, _string);
        }

        Type(String s, Charset cs)
        {
            _string = s;
            _base = this;
            _buffer = BufferUtil.toBuffer(s);
            _charset = cs;
            _charsetString = _charset == null ? null : _charset.toString().toLowerCase(Locale.ENGLISH);
            _assumedCharset = true;
            _field = new PreEncodedHttpField(HttpHeader.CONTENT_TYPE, _string);
        }

        public ByteBuffer asBuffer()
        {
            return _buffer.asReadOnlyBuffer();
        }

        public Charset getCharset()
        {
            return _charset;
        }

        public String getCharsetString()
        {
            return _charsetString;
        }

        public boolean is(String s)
        {
            return _string.equalsIgnoreCase(s);
        }

        public String asString()
        {
            return _string;
        }

        @Override
        public String toString()
        {
            return _string;
        }

        public boolean isCharsetAssumed()
        {
            return _assumedCharset;
        }

        public HttpField getContentTypeField()
        {
            return _field;
        }

        public Type getBaseType()
        {
            return _base;
        }
    }

    public static final Index<Type> CACHE = new Index.Builder<Type>()
        .caseSensitive(false)
        .withAll(() ->
        {
            Map<String, Type> result = new HashMap<>();
            for (Type type : Type.values())
            {
                String key1 = type.toString();
                result.put(key1, type);

                if (key1.indexOf(";charset=") > 0)
                {
                    String key2 = StringUtil.replace(key1, ";charset=", "; charset=");
                    result.put(key2, type);
                }
            }
            return result;
        })
        .build();

    static
    {
        for (MimeTypes.Type type : MimeTypes.Type.values())
        {
            if (type.isCharsetAssumed())
                __assumedEncodings.put(type.asString(), type.getCharsetString());
        }

        String resourceName = "mime.properties";
        try (InputStream stream = MimeTypes.class.getResourceAsStream(resourceName))
        {
            if (stream == null)
            {
                LOG.warn("Missing mime-type resource: {}", resourceName);
            }
            else
            {
                try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8))
                {
                    Properties props = new Properties();
                    props.load(reader);
                    props.stringPropertyNames().stream()
                        .filter(x -> x != null)
                        .forEach(x ->
                            __dftMimeMap.put(StringUtil.asciiToLowerCase(x), normalizeMimeType(props.getProperty(x))));

                    if (__dftMimeMap.isEmpty())
                    {
                        LOG.warn("Empty mime types at {}", resourceName);
                    }
                    else if (__dftMimeMap.size() < props.keySet().size())
                    {
                        LOG.warn("Duplicate or null mime-type extension in resource: {}", resourceName);
                    }
                }
                catch (IOException e)
                {
                    if (LOG.isDebugEnabled())
                        LOG.warn("Unable to read mime-type resource: {}", resourceName, e);
                    else
                        LOG.warn("Unable to read mime-type resource: {} - {}", resourceName, e.toString());
                }
            }
        }
        catch (IOException e)
        {
            if (LOG.isDebugEnabled())
                LOG.warn("Unable to load mime-type resource: {}", resourceName, e);
            else
                LOG.warn("Unable to load mime-type resource: {} - {}", resourceName, e.toString());
        }

        resourceName = "encoding.properties";
        try (InputStream stream = MimeTypes.class.getResourceAsStream(resourceName))
        {
            if (stream == null)
                LOG.warn("Missing encoding resource: {}", resourceName);
            else
            {
                try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8))
                {
                    Properties props = new Properties();
                    props.load(reader);
                    props.stringPropertyNames().stream()
                        .filter(t -> t != null)
                        .forEach(t ->
                        {
                            String charset = props.getProperty(t);
                            if (charset.startsWith("-"))
                                __assumedEncodings.put(t, charset.substring(1));
                            else
                                __inferredEncodings.put(t, props.getProperty(t));
                        });

                    if (__inferredEncodings.isEmpty())
                    {
                        LOG.warn("Empty encodings at {}", resourceName);
                    }
                    else if ((__inferredEncodings.size() + __assumedEncodings.size()) < props.keySet().size())
                    {
                        LOG.warn("Null or duplicate encodings in resource: {}", resourceName);
                    }
                }
                catch (IOException e)
                {
                    if (LOG.isDebugEnabled())
                        LOG.warn("Unable to read encoding resource: {}", resourceName, e);
                    else
                        LOG.warn("Unable to read encoding resource: {} - {}", resourceName, e.toString());
                }
            }
        }
        catch (IOException e)
        {
            if (LOG.isDebugEnabled())
                LOG.warn("Unable to load encoding resource: {}", resourceName, e);
            else
                LOG.warn("Unable to load encoding resource: {} - {}", resourceName, e.toString());
        }
    }

    private final Map<String, String> _mimeMap = new HashMap<String, String>();

    /**
     * Constructor.
     */
    public MimeTypes()
    {
    }

    public Map<String, String> getMimeMap()
    {
        return _mimeMap;
    }

    /**
     * @param mimeMap A Map of file extension to mime-type.
     */
    public void setMimeMap(Map<String, String> mimeMap)
    {
        _mimeMap.clear();
        if (mimeMap != null)
        {
            for (Entry<String, String> ext : mimeMap.entrySet())
            {
                _mimeMap.put(StringUtil.asciiToLowerCase(ext.getKey()), normalizeMimeType(ext.getValue()));
            }
        }
    }

    /**
     * Get the MIME type by filename extension.
     * Lookup only the static default mime map.
     *
     * @param filename A file name
     * @return MIME type matching the longest dot extension of the
     * file name.
     */
    public static String getDefaultMimeByExtension(String filename)
    {
        String type = null;

        if (filename != null)
        {
            int i = -1;
            while (type == null)
            {
                i = filename.indexOf(".", i + 1);

                if (i < 0 || i >= filename.length())
                    break;

                String ext = StringUtil.asciiToLowerCase(filename.substring(i + 1));
                if (type == null)
                    type = __dftMimeMap.get(ext);
            }
        }

        if (type == null)
        {
            type = __dftMimeMap.get("*");
        }

        return type;
    }

    /**
     * Get the MIME type by filename extension.
     * Lookup the content and static default mime maps.
     *
     * @param filename A file name
     * @return MIME type matching the longest dot extension of the
     * file name.
     */
    public String getMimeByExtension(String filename)
    {
        String type = null;

        if (filename != null)
        {
            int i = -1;
            while (type == null)
            {
                i = filename.indexOf(".", i + 1);

                if (i < 0 || i >= filename.length())
                    break;

                String ext = StringUtil.asciiToLowerCase(filename.substring(i + 1));
                if (_mimeMap != null)
                    type = _mimeMap.get(ext);
                if (type == null)
                    type = __dftMimeMap.get(ext);
            }
        }

        if (type == null)
        {
            if (_mimeMap != null)
                type = _mimeMap.get("*");
            if (type == null)
                type = __dftMimeMap.get("*");
        }

        return type;
    }

    /**
     * Set a mime mapping
     *
     * @param extension the extension
     * @param type the mime type
     */
    public void addMimeMapping(String extension, String type)
    {
        _mimeMap.put(StringUtil.asciiToLowerCase(extension), normalizeMimeType(type));
    }

    public static Set<String> getKnownMimeTypes()
    {
        return new HashSet<>(__dftMimeMap.values());
    }

    private static String normalizeMimeType(String type)
    {
        MimeTypes.Type t = CACHE.get(type);
        if (t != null)
            return t.asString();

        return StringUtil.asciiToLowerCase(type);
    }

    public static String getCharsetFromContentType(String value)
    {
        if (value == null)
            return null;
        int end = value.length();
        int state = 0;
        int start = 0;
        boolean quote = false;
        int i = 0;
        for (; i < end; i++)
        {
            char b = value.charAt(i);

            if (quote && state != 10)
            {
                if ('"' == b)
                    quote = false;
                continue;
            }

            if (';' == b && state <= 8)
            {
                state = 1;
                continue;
            }

            switch (state)
            {
                case 0:
                    if ('"' == b)
                    {
                        quote = true;
                        break;
                    }
                    break;

                case 1:
                    if ('c' == b)
                        state = 2;
                    else if (' ' != b)
                        state = 0;
                    break;
                case 2:
                    if ('h' == b)
                        state = 3;
                    else
                        state = 0;
                    break;
                case 3:
                    if ('a' == b)
                        state = 4;
                    else
                        state = 0;
                    break;
                case 4:
                    if ('r' == b)
                        state = 5;
                    else
                        state = 0;
                    break;
                case 5:
                    if ('s' == b)
                        state = 6;
                    else
                        state = 0;
                    break;
                case 6:
                    if ('e' == b)
                        state = 7;
                    else
                        state = 0;
                    break;
                case 7:
                    if ('t' == b)
                        state = 8;
                    else
                        state = 0;
                    break;
                case 8:
                    if ('=' == b)
                        state = 9;
                    else if (' ' != b)
                        state = 0;
                    break;
                case 9:
                    if (' ' == b)
                        break;
                    if ('"' == b)
                    {
                        quote = true;
                        start = i + 1;
                        state = 10;
                        break;
                    }
                    start = i;
                    state = 10;
                    break;
                case 10:
                    if (!quote && (';' == b || ' ' == b) ||
                        (quote && '"' == b))
                        return StringUtil.normalizeCharset(value, start, i - start);
                    break;
                default:
                    throw new IllegalStateException();
            }
        }

        if (state == 10)
            return StringUtil.normalizeCharset(value, start, i - start);

        return null;
    }

    /**
     * Access a mutable map of mime type to the charset inferred from that content type.
     * An inferred encoding is used by when encoding/decoding a stream and is
     * explicitly set in any metadata (eg Content-Type).
     *
     * @return Map of mime type to charset
     */
    public static Map<String, String> getInferredEncodings()
    {
        return __inferredEncodings;
    }

    /**
     * Access a mutable map of mime type to the charset assumed for that content type.
     * An assumed encoding is used by when encoding/decoding a stream, but is not
     * explicitly set in any metadata (eg Content-Type).
     *
     * @return Map of mime type to charset
     */
    public static Map<String, String> getAssumedEncodings()
    {
        return __assumedEncodings;
    }

    public static String getCharsetInferredFromContentType(String contentType)
    {
        return __inferredEncodings.get(contentType);
    }

    public static String getCharsetAssumedFromContentType(String contentType)
    {
        return __assumedEncodings.get(contentType);
    }

    public static String getContentTypeWithoutCharset(String value)
    {
        int end = value.length();
        int state = 0;
        int start = 0;
        boolean quote = false;
        int i = 0;
        StringBuilder builder = null;
        for (; i < end; i++)
        {
            char b = value.charAt(i);

            if ('"' == b)
            {
                if (quote)
                {
                    quote = false;
                }
                else
                {
                    quote = true;
                }

                switch (state)
                {
                    case 11:
                        builder.append(b);
                        break;
                    case 10:
                        break;
                    case 9:
                        builder = new StringBuilder();
                        builder.append(value, 0, start + 1);
                        state = 10;
                        break;
                    default:
                        start = i;
                        state = 0;
                }
                continue;
            }

            if (quote)
            {
                if (builder != null && state != 10)
                    builder.append(b);
                continue;
            }

            switch (state)
            {
                case 0:
                    if (';' == b)
                        state = 1;
                    else if (' ' != b)
                        start = i;
                    break;

                case 1:
                    if ('c' == b)
                        state = 2;
                    else if (' ' != b)
                        state = 0;
                    break;
                case 2:
                    if ('h' == b)
                        state = 3;
                    else
                        state = 0;
                    break;
                case 3:
                    if ('a' == b)
                        state = 4;
                    else
                        state = 0;
                    break;
                case 4:
                    if ('r' == b)
                        state = 5;
                    else
                        state = 0;
                    break;
                case 5:
                    if ('s' == b)
                        state = 6;
                    else
                        state = 0;
                    break;
                case 6:
                    if ('e' == b)
                        state = 7;
                    else
                        state = 0;
                    break;
                case 7:
                    if ('t' == b)
                        state = 8;
                    else
                        state = 0;
                    break;
                case 8:
                    if ('=' == b)
                        state = 9;
                    else if (' ' != b)
                        state = 0;
                    break;
                case 9:
                    if (' ' == b)
                        break;
                    builder = new StringBuilder();
                    builder.append(value, 0, start + 1);
                    state = 10;
                    break;
                case 10:
                    if (';' == b)
                    {
                        builder.append(b);
                        state = 11;
                    }
                    break;
                case 11:
                    if (' ' != b)
                        builder.append(b);
                    break;
                default:
                    throw new IllegalStateException();
            }
        }
        if (builder == null)
            return value;
        return builder.toString();
    }
}
