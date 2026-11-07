import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.Base64;

/** Offline release signing. Private material stays outside the source tree. */
class ReleaseSigning {
    public static void main(String[] args) throws Exception {
        Path secret = Path.of(".secrets/updates-private.pk8");
        Path published = Path.of("desktop/src/main/resources/update-public-key.der");
        if (args.length == 1 && args[0].equals("generate")) {
            if (Files.exists(secret) || Files.exists(published)) { System.out.println("Existing signing material retained."); return; }
            var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            Files.createDirectories(secret.getParent()); Files.createDirectories(published.getParent());
            Files.write(secret, pair.getPrivate().getEncoded(), StandardOpenOption.CREATE_NEW);
            Files.write(published, pair.getPublic().getEncoded(), StandardOpenOption.CREATE_NEW);
            System.out.println("Release key generated; only the public key belongs in source control.");
        } else if (args.length == 2 && args[0].equals("sign")) {
            Path manifest = Path.of(args[1]);
            var key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(secret)));
            var signature = Signature.getInstance("Ed25519"); signature.initSign(key); signature.update(Files.readAllBytes(manifest));
            Files.writeString(Path.of(args[1] + ".sig"), Base64.getEncoder().encodeToString(signature.sign()));
            System.out.println("Release manifest signed.");
        } else if (args.length == 2 && args[0].equals("verify")) {
            var key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Files.readAllBytes(published)));
            var signature = Signature.getInstance("Ed25519"); signature.initVerify(key); signature.update(Files.readAllBytes(Path.of(args[1])));
            if (!signature.verify(Base64.getDecoder().decode(Files.readString(Path.of(args[1] + ".sig")).trim())))
                throw new SignatureException("Release manifest signature is invalid.");
            System.out.println("Release manifest signature verified.");
        } else throw new IllegalArgumentException("Use: generate | sign updates.json | verify updates.json");
    }
}
