/*
 * acme4j - Java ACME client
 *
 * Copyright (C) 2015 Richard "Shred" Körber
 *   http://acme4j.shredzone.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */
package org.shredzone.acme4j.connector;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyPair;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.EnumMap;
import java.util.Map;

import org.jose4j.base64url.Base64Url;
import org.jose4j.json.JsonUtil;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.lang.JoseException;
import org.shredzone.acme4j.Account;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.exception.AcmeServerException;
import org.shredzone.acme4j.provider.AcmeClientProvider;
import org.shredzone.acme4j.util.ClaimBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connects to the ACME server and offers different methods for invoking the API.
 *
 * @author Richard "Shred" Körber
 */
public class Connection implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Connection.class);

    private final AcmeClientProvider provider;
    private HttpURLConnection conn;

    public Connection(AcmeClientProvider provider) {
        this.provider = provider;
    }

    @Override
    public void close() {
        conn = null;
    }

    /**
     * Forcedly starts a new {@link Session}. Usually this method is not required, as a
     * session is automatically started if necessary.
     *
     * @param uri
     *            {@link URI} a HEAD request is sent to for starting the session
     * @param session
     *            {@link Session} instance to be used for tracking
     */
    public void startSession(URI uri, Session session) throws AcmeException {
        try {
            LOG.debug("Initial replay nonce from {}", uri);
            HttpURLConnection conn = provider.openConnection(uri);
            conn.setRequestMethod("HEAD");
            conn.setRequestProperty("Accept-Charset", "utf-8");
            conn.connect();

            session.setNonce(getNonceFromHeader(conn));
        } catch (IOException ex) {
            throw new AcmeException("Failed to request a nonce", ex);
        }
    }

    /**
     * Sends a simple GET request.
     *
     * @param uri
     *            {@link URI} to send the request to.
     * @return HTTP response code
     */
    public int sendRequest(URI uri) throws AcmeException {
        try {
            LOG.debug("GET {}", uri);

            conn = provider.openConnection(uri);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept-Charset", "utf-8");
            conn.setDoOutput(false);

            conn.connect();

            throwException();

            return conn.getResponseCode();
        } catch (IOException ex) {
            throw new AcmeException("API access failed", ex);
        }
    }

    /**
     * Sends a signed POST request.
     *
     * @param uri
     *            {@link URI} to send the request to.
     * @param claims
     *            {@link ClaimBuilder} containing claims. Must not be {@code null}.
     * @param session
     *            {@link Session} instance to be used for tracking
     * @param account
     *            {@link Account} to be used for signing the request
     * @return HTTP response code
     */
    public int sendSignedRequest(URI uri, ClaimBuilder claims, Session session, Account account) throws AcmeException {
        try {
            KeyPair keypair = account.getKeyPair();

            if (session.getNonce() == null) {
                startSession(uri, session);
            }
            if (session.getNonce() == null) {
                throw new AcmeException("No nonce available");
            }

            LOG.debug("POST {} with claims: {}", uri, claims);

            conn = provider.openConnection(uri);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Charset", "utf-8");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            final JsonWebKey jwk = JsonWebKey.Factory.newJwk(keypair.getPublic());

            JsonWebSignature jws = new JsonWebSignature();
            jws.setPayload(claims.toString());
            jws.getHeaders().setObjectHeaderValue("nonce", Base64Url.encode(session.getNonce()));
            jws.getHeaders().setJwkHeaderValue("jwk", jwk);
            jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);
            jws.setKey(keypair.getPrivate());
            byte[] outputData = jws.getCompactSerialization().getBytes("utf-8");

            conn.setFixedLengthStreamingMode(outputData.length);
            conn.connect();

            try (OutputStream out = conn.getOutputStream()) {
                out.write(outputData);
            }

            session.setNonce(getNonceFromHeader(conn));

            throwException();

            return conn.getResponseCode();
        } catch (JoseException | IOException ex) {
            throw new AcmeException("Failed to send request to " + uri, ex);
        }
    }

    /**
     * Reads a server response as JSON data.
     *
     * @return Map containing the parsed JSON data
     */
    public Map<String, Object> readJsonResponse() throws AcmeException {
        if (conn == null) {
            throw new IllegalStateException("Not connected");
        }

        StringBuilder sb = new StringBuilder();
        Map<String, Object> result = null;

        try {
            InputStream in = (conn.getResponseCode() < 400 ? conn.getInputStream() : conn.getErrorStream());
            if (in != null) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "utf-8"))) {
                    sb.append(r.readLine());
                }

                result = JsonUtil.parseJson(sb.toString());
                LOG.debug("Result JSON: {}", sb);
            }

        } catch (JoseException | IOException ex) {
            throw new AcmeException("Failed to parse response: " + sb, ex);
        }

        return result;
    }

    /**
     * Reads a certificate.
     *
     * @return {@link X509Certificate} that was read.
     */
    public X509Certificate readCertificate() throws AcmeException {
        if (conn == null) {
            throw new IllegalStateException("Not connected");
        }

        try (InputStream in = conn.getInputStream()) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(in);
        } catch (IOException ex) {
            throw new AcmeException("Failed to read certificate", ex);
        } catch (CertificateException ex) {
            throw new AcmeException("Error while generating the X.509 certificate", ex);
        }
    }

    /**
     * Reads a resource directory.
     *
     * @return Map of {@link Resource} and the respective {@link URI} to invoke
     */
    public Map<Resource, URI> readResourceMap() throws AcmeException {
        EnumMap<Resource, URI> resourceMap = new EnumMap<>(Resource.class);

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"))) {
            sb.append(reader.readLine());
        } catch (IOException ex) {
            throw new AcmeException("Could not read resource map", ex);
        }

        try {
            Map<String, Object> result = JsonUtil.parseJson(sb.toString());
            for (Map.Entry<String, Object> entry : result.entrySet()) {
                Resource res = Resource.parse(entry.getKey());
                if (res != null) {
                    URI uri = new URI(entry.getValue().toString());
                    resourceMap.put(res, uri);
                }
            }

            LOG.debug("Resource directory: {}", resourceMap);
        } catch (JoseException | URISyntaxException ex) {
            throw new AcmeException("Could not parse resource map: " + sb, ex);
        }

        return resourceMap;
    }

    /**
     * Gets a location from the {@code Location} header.
     *
     * @return Location {@link URI}, or {@code null} if no Location header was set
     */
    public URI getLocation() throws AcmeException {
        String location = conn.getHeaderField("Location");
        if (location == null) {
            return null;
        }

        try {
            LOG.debug("Location: {}", location);
            return new URI(location);
        } catch (URISyntaxException ex) {
            throw new AcmeException("Bad Location header: " + location);
        }
    }

    /**
     * Checks if the server returned an error, and if so, throws a {@link AcmeException}.
     *
     * @throws AcmeException
     *             if the server returned a JSON problem
     */
    private void throwException() throws AcmeException {
        if ("application/problem+json".equals(conn.getHeaderField("Content-Type"))) {
            Map<String, Object> map = readJsonResponse();
            String type = (String) map.get("type");
            String detail = (String) map.get("detail");
            throw new AcmeServerException(type, detail);
        }
    }

    /**
     * Extracts a nonce from the header.
     *
     * @param conn
     *            {@link HttpURLConnection} to read the headers from
     * @return Nonce
     * @throws AcmeException
     *             if there was no {@code Replay-Nonce} header, or the nonce was invalid
     */
    private static byte[] getNonceFromHeader(HttpURLConnection conn) throws AcmeException {
        String nonceHeader = conn.getHeaderField("Replay-Nonce");
        if (nonceHeader == null) {
            throw new AcmeException("No replay nonce");
        }

        LOG.debug("Replay Nonce: {}", nonceHeader);

        return Base64Url.decode(nonceHeader);
    }

}
