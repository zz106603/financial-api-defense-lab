import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/** 로컬 합성 주체만 발급하는 독립 개발 도구. 제품 소스/배포 JAR에 포함하지 않는다. */
public class DevTokens {
    public static void main(String[] args) {
        try {
            if (args.length != 2 || !(args[0].equals("init") || args[0].equals("tokens"))) {
                throw new IllegalArgumentException("Expected init|tokens and runtime directory");
            }
            Path directory = Path.of(args[1]).toAbsolutePath().normalize();
            Files.createDirectories(directory);
            Path privateFile = directory.resolve("private.pem");
            Path publicFile = directory.resolve("public.pem");
            Path secretsFile = directory.resolve("audit-hmac.key");
            boolean any = Files.exists(privateFile) || Files.exists(publicFile) || Files.exists(secretsFile);
            boolean all = Files.exists(privateFile) && Files.exists(publicFile) && Files.exists(secretsFile);
            if (any && !all) {
                throw new IllegalStateException("Incomplete credentials; refusing replacement");
            }
            if (!all) {
                if (!args[0].equals("init")) {
                    throw new IllegalStateException("Initialize credentials first");
                }
                var generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                var keys = generator.generateKeyPair();
                writeNew(privateFile, pem("PRIVATE KEY", keys.getPrivate().getEncoded()));
                writeNew(publicFile, pem("PUBLIC KEY", keys.getPublic().getEncoded()));
                var random = new SecureRandom();
                byte[] hmac = new byte[32];
                random.nextBytes(hmac);
                writeNew(secretsFile, Base64.getEncoder().encodeToString(hmac) + "\n");
            }

            var factory = KeyFactory.getInstance("RSA");
            var privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(readPem(privateFile, "PRIVATE KEY")));
            var publicKey = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(readPem(publicFile, "PUBLIC KEY")));
            if (publicKey.getModulus().bitLength() < 2048) {
                throw new IllegalStateException("RSA key too small");
            }
            long now = Instant.now().getEpochSecond();
            var json = new StringBuilder("{\n");
            String[] subjects = {"customer-a", "customer-b", "partner-x", "partner-y"};
            for (int i = 0; i < subjects.length; i++) {
                String subject = subjects[i];
                String type = subject.startsWith("customer-") ? "CUSTOMER" : "PARTNER";
                String payload = "{\"iss\":\"https://issuer.local.invalid\",\"aud\":[\"financial-api-defense-lab\"],"
                        + "\"sub\":\"" + subject + "\",\"principal_type\":\"" + type
                        + "\",\"iat\":" + now + ",\"nbf\":" + (now - 5) + ",\"exp\":" + (now + 1800) + "}";
                String input = encoded("{\"alg\":\"RS256\",\"typ\":\"JWT\"}") + "." + encoded(payload);
                var signer = Signature.getInstance("SHA256withRSA");
                signer.initSign(privateKey);
                signer.update(input.getBytes(StandardCharsets.US_ASCII));
                byte[] signature = signer.sign();
                signer.initVerify(publicKey);
                signer.update(input.getBytes(StandardCharsets.US_ASCII));
                if (!signer.verify(signature)) {
                    throw new IllegalStateException("Key pair mismatch");
                }
                json.append("  \"").append(subject).append("\":\"")
                        .append(input).append('.').append(Base64.getUrlEncoder().withoutPadding().encodeToString(signature))
                        .append('"').append(i + 1 == subjects.length ? "\n" : ",\n");
            }
            Files.writeString(directory.resolve("tokens.json"), json.append("}\n"), StandardCharsets.UTF_8);
            System.out.println("Development credentials ready; tokens valid for 30 minutes.");
        } catch (Exception error) {
            // 키·토큰·비밀 및 파일 원문은 오류 출력에 포함하지 않는다.
            System.err.println("Development credential operation failed: " + error.getClass().getSimpleName());
            System.exit(1);
        }
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String pem(String kind, byte[] bytes) {
        return "-----BEGIN " + kind + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes)
                + "\n-----END " + kind + "-----\n";
    }

    private static byte[] readPem(Path path, String kind) throws Exception {
        String value = Files.readString(path, StandardCharsets.US_ASCII)
                .replace("-----BEGIN " + kind + "-----", "")
                .replace("-----END " + kind + "-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(value);
    }

    private static void writeNew(Path path, String value) throws Exception {
        Files.writeString(path, value, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
}
