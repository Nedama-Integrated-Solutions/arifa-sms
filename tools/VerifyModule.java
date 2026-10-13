import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Checks a signed Ignition module (.modl) without needing a gateway.
 *
 * Run it with the JDK, no build step needed:
 *     java tools\VerifyModule.java path\to\Arifa-SMS-Notification-signed.modl
 *
 * It confirms that every file in the module has a signature, that every signature matches its file, and that
 * all of them were made by the certificate stored in the module (certificates.p7b). It also prints the
 * certificate's name, dates, SHA-256 fingerprint and SHA-1 thumbprint (the value Ignition's install dialog shows). Exit code 0 means everything checks out, 1 means it does not.
 *
 * This checks that the module is consistent. It does not say whether a gateway will trust the certificate:
 * for a self-signed certificate the person installing the module accepts it once.
 */
public class VerifyModule {

    private static final String SIGNATURES = "signatures.properties";
    private static final String CHAIN = "certificates.p7b";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: java VerifyModule.java <module.modl>");
            System.exit(2);
        }
        int code = verify(new File(args[0]));
        System.exit(code);
    }

    static int verify(File module) throws Exception {
        if (!module.isFile()) {
            return fail("File not found: " + module);
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(module)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory()) {
                    try (var in = zip.getInputStream(entry)) {
                        files.put(entry.getName(), in.readAllBytes());
                    }
                }
            }
        } catch (IOException e) {
            return fail("Not a readable module file: " + e.getMessage());
        }

        byte[] sigBytes = files.remove(SIGNATURES);
        byte[] chainBytes = files.remove(CHAIN);
        if (sigBytes == null || chainBytes == null) {
            return fail("This module is NOT signed: " + (sigBytes == null ? SIGNATURES : CHAIN) + " is missing.");
        }

        Properties signatures = new Properties();
        signatures.load(new ByteArrayInputStream(sigBytes));

        Collection<? extends Certificate> certs;
        try {
            certs = CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(chainBytes));
        } catch (Exception e) {
            return fail("certificates.p7b could not be read: " + e.getMessage());
        }
        if (certs.isEmpty()) {
            return fail("certificates.p7b contains no certificate.");
        }

        // Every file needs a signature, and every signature needs a file.
        List<String> problems = new ArrayList<>();
        TreeSet<String> expected = new TreeSet<>();
        for (String name : files.keySet()) {
            expected.add("/" + name);
        }
        for (String name : expected) {
            if (signatures.getProperty(name) == null) {
                problems.add("no signature for " + name);
            }
        }
        for (String name : signatures.stringPropertyNames()) {
            if (!expected.contains(name)) {
                problems.add("signature for a file that is not in the module: " + name);
            }
        }
        if (!problems.isEmpty()) {
            return fail("The file list and the signature list do not match: " + String.join("; ", problems));
        }

        // Find the certificate whose key made the signatures.
        X509Certificate signer = null;
        List<String> mismatches = new ArrayList<>();
        for (Certificate candidate : certs) {
            mismatches.clear();
            for (String name : expected) {
                if (!matches(candidate, files.get(name.substring(1)), signatures.getProperty(name))) {
                    mismatches.add(name);
                }
            }
            if (mismatches.isEmpty()) {
                signer = (X509Certificate) candidate;
                break;
            }
        }
        if (signer == null) {
            return fail("The signatures do not match the module's files and certificate. Either a file changed after "
                    + "signing, or the module was signed with a different key than the certificate in "
                    + "certificates.p7b (wrong keystore alias or wrong chain file). First mismatch: "
                    + (mismatches.isEmpty() ? "?" : mismatches.get(0)));
        }

        Instant now = Instant.now();
        System.out.println("Module:       " + module.getName());
        System.out.println("Signed by:    " + signer.getSubjectX500Principal().getName());
        System.out.println("Valid from:   " + signer.getNotBefore().toInstant());
        System.out.println("Valid until:  " + signer.getNotAfter().toInstant());
        System.out.println("SHA-256:      " + fingerprint(signer, "SHA-256"));
        System.out.println("SHA-1:        " + fingerprint(signer, "SHA-1") + "   (Ignition's install dialog calls this the Thumbprint)");
        System.out.println("Files:        " + expected.size() + " files, all signatures match");
        if (now.isBefore(signer.getNotBefore().toInstant()) || now.isAfter(signer.getNotAfter().toInstant())) {
            return fail("The signing certificate is not valid today.");
        }
        long daysLeft = ChronoUnit.DAYS.between(now, signer.getNotAfter().toInstant());
        if (daysLeft < 365) {
            System.out.println("WARNING: the signing certificate expires in " + daysLeft + " days.");
        }
        System.out.println("RESULT: OK. The module is consistent and signed by the certificate above.");
        return 0;
    }

    private static boolean matches(Certificate cert, byte[] content, String signatureBase64) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(cert.getPublicKey());
            signature.update(content);
            return signature.verify(Base64.getDecoder().decode(signatureBase64.trim()));
        } catch (Exception e) {
            return false;
        }
    }

    private static String fingerprint(X509Certificate cert, String algorithm) throws Exception {
        byte[] digest = MessageDigest.getInstance(algorithm).digest(cert.getEncoded());
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < digest.length; i++) {
            if (i > 0) {
                out.append(':');
            }
            out.append(String.format("%02X", digest[i]));
        }
        return out.toString();
    }

    private static int fail(String message) {
        System.out.println("RESULT: FAILED. " + message);
        return 1;
    }
}
