package com.telerad.gateway;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class GatewayCaService {
    private final GatewayProperties props;

    private X509Certificate caCert;
    private PrivateKey caKey;

    public record IssuedCert(
            String certificatePem,
            String caCertificatePem,
            String serialHex,
            String fingerprintSha256,
            Instant notBefore,
            Instant notAfter
    ) {}

    @PostConstruct
    void init() {
        try {
            Path dir = Path.of(props.getCaDir()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path certPath = dir.resolve(props.getCaCertFile());
            Path keyPath = dir.resolve(props.getCaKeyFile());
            if (!Files.exists(certPath) || !Files.exists(keyPath)) {
                log.info("Generating TeleRad gateway CA under {}", dir);
                generateCa(certPath, keyPath);
            }
            caCert = readCertificate(certPath);
            caKey = readPrivateKey(keyPath);
            ensureServerKeystore(dir);
            log.info("Gateway CA ready: subject={}", caCert.getSubjectX500Principal());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize gateway CA: " + e.getMessage(), e);
        }
    }

    public IssuedCert signCsr(String csrPem, UUID gatewayId) {
        try {
            PKCS10CertificationRequest csr = readCsr(csrPem);
            Instant notBefore = Instant.now().minus(5, ChronoUnit.MINUTES);
            Instant notAfter = Instant.now().plus(props.getCertValidityDays(), ChronoUnit.DAYS);
            BigInteger serial = new BigInteger(64, new SecureRandom()).abs();

            X500Name issuer = X500Name.getInstance(caCert.getSubjectX500Principal().getEncoded());
            // Bind CN to gateway id for clarity
            X500Name subject = new X500Name("CN=gateway-" + gatewayId);

            ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA").build(caKey);
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    issuer,
                    serial,
                    Date.from(notBefore),
                    Date.from(notAfter),
                    subject,
                    csr.getSubjectPublicKeyInfo()
            );
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));

            X509CertificateHolder holder = builder.build(signer);
            X509Certificate cert = new JcaX509CertificateConverter().getCertificate(holder);
            cert.verify(caCert.getPublicKey());

            return new IssuedCert(
                    toPem("CERTIFICATE", cert.getEncoded()),
                    toPem("CERTIFICATE", caCert.getEncoded()),
                    serial.toString(16),
                    fingerprintSha256(cert.getEncoded()),
                    notBefore,
                    notAfter
            );
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid CSR: " + e.getMessage());
        }
    }

    public X509Certificate caCertificate() {
        return caCert;
    }

    public static String fingerprintSha256(byte[] der) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(der));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void generateCa(Path certPath, Path keyPath) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(4096);
        KeyPair kp = kpg.generateKeyPair();
        Instant notBefore = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant notAfter = Instant.now().plus(3650, ChronoUnit.DAYS);
        X500Name subject = new X500Name("CN=TeleRad Gateway CA,O=TeleRad");
        BigInteger serial = BigInteger.valueOf(1);
        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA").build(kp.getPrivate());
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, serial, Date.from(notBefore), Date.from(notAfter), subject, kp.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(builder.build(signer));
        Files.writeString(certPath, toPem("CERTIFICATE", cert.getEncoded()), StandardCharsets.UTF_8);
        try (JcaPEMWriter w = new JcaPEMWriter(Files.newBufferedWriter(keyPath))) {
            w.writeObject(kp.getPrivate());
        }
    }

    private void ensureServerKeystore(Path dir) throws Exception {
        Path p12 = Path.of(props.getServerKeystore()).toAbsolutePath().normalize();
        Path trust = dir.resolve("truststore.p12");
        if (Files.exists(p12) && Files.exists(trust) && serverCertCovers(p12, serverNames())) return;
        Files.createDirectories(p12.getParent());
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        Instant notBefore = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant notAfter = Instant.now().plus(825, ChronoUnit.DAYS);
        X500Name issuer = X500Name.getInstance(caCert.getSubjectX500Principal().getEncoded());
        X500Name subject = new X500Name("CN=telerad-gateway-api");
        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA").build(caKey);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuer,
                BigInteger.valueOf(System.currentTimeMillis()),
                Date.from(notBefore),
                Date.from(notAfter),
                subject,
                kp.getPublic()
        );
        builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(
                serverNames().stream().map(GatewayCaService::generalName).toArray(GeneralName[]::new)));
        X509Certificate serverCert = new JcaX509CertificateConverter().getCertificate(builder.build(signer));
        serverCert.verify(caCert.getPublicKey());
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        char[] pass = props.getServerKeystorePassword().toCharArray();
        // Leaf only — CA is in the truststore; avoids PKCS12 chain validation quirks
        ks.setKeyEntry("server", kp.getPrivate(), pass, new java.security.cert.Certificate[]{serverCert});
        try (OutputStream out = Files.newOutputStream(p12)) {
            ks.store(out, pass);
        }
        KeyStore ts = KeyStore.getInstance("PKCS12");
        ts.load(null, null);
        ts.setCertificateEntry("gateway-ca", caCert);
        try (OutputStream out = Files.newOutputStream(trust)) {
            ts.store(out, pass);
        }
        log.info("Wrote gateway mTLS server keystore {}", p12);
    }

    /** Built-in names plus the configured public host names of the mTLS port. */
    java.util.List<String> serverNames() {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>(
                java.util.List.of("localhost", "telerad-gateway-api", "127.0.0.1"));
        for (String n : props.getServerHostnames() == null ? java.util.List.<String>of() : props.getServerHostnames()) {
            if (n != null && !n.isBlank()) names.add(n.trim().toLowerCase(java.util.Locale.ROOT));
        }
        return new java.util.ArrayList<>(names);
    }

    static boolean isIpLiteral(String name) {
        return name.matches("[0-9.]+") || name.contains(":");
    }

    static GeneralName generalName(String name) {
        return new GeneralName(isIpLiteral(name) ? GeneralName.iPAddress : GeneralName.dNSName, name);
    }

    /** True when the existing server certificate already names every required host. */
    private boolean serverCertCovers(Path p12, java.util.List<String> names) {
        try (java.io.InputStream in = Files.newInputStream(p12)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(in, props.getServerKeystorePassword().toCharArray());
            X509Certificate cert = (X509Certificate) ks.getCertificate("server");
            java.util.Set<String> have = new java.util.HashSet<>();
            if (cert.getSubjectAlternativeNames() != null) {
                for (java.util.List<?> san : cert.getSubjectAlternativeNames()) {
                    have.add(String.valueOf(san.get(1)).toLowerCase(java.util.Locale.ROOT));
                }
            }
            boolean ok = have.containsAll(names);
            if (!ok) log.info("Re-issuing gateway mTLS server certificate for {}", names);
            return ok;
        } catch (Exception e) {
            log.warn("Cannot read gateway server keystore ({}); re-issuing", e.getMessage());
            return false;
        }
    }

    private static PKCS10CertificationRequest readCsr(String pem) throws IOException {
        try (PemReader reader = new PemReader(new StringReader(pem))) {
            PemObject obj = reader.readPemObject();
            if (obj == null) throw new IllegalArgumentException("Empty CSR PEM");
            return new PKCS10CertificationRequest(obj.getContent());
        }
    }

    private static X509Certificate readCertificate(Path path) throws Exception {
        try (PemReader reader = new PemReader(Files.newBufferedReader(path))) {
            PemObject obj = reader.readPemObject();
            return new JcaX509CertificateConverter().getCertificate(new X509CertificateHolder(obj.getContent()));
        }
    }

    private static PrivateKey readPrivateKey(Path path) throws Exception {
        try (PEMParser parser = new PEMParser(Files.newBufferedReader(path))) {
            Object obj = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            if (obj instanceof PEMKeyPair pair) {
                return converter.getKeyPair(pair).getPrivate();
            }
            if (obj instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo info) {
                return converter.getPrivateKey(info);
            }
            throw new IllegalStateException("Unsupported CA key format");
        }
    }

    private static String toPem(String type, byte[] der) throws IOException {
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter w = new JcaPEMWriter(sw)) {
            w.writeObject(new PemObject(type, der));
        }
        return sw.toString();
    }

    @SuppressWarnings("unused")
    private static boolean isRsa(PrivateKey key) {
        return key instanceof RSAPrivateKey;
    }
}
