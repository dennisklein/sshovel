// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

// Stand-in for the Android Keystore signer in M0 spike 4. It generates an
// EC P-256 key with the JDK provider and signs raw digests with
// NONEwithECDSA, which is the exact JCA call the Kotlin PlatformBridge makes
// against AndroidKeyStore. Protocol on stdio: first line out is the base64
// PKIX public key; then each hex digest line in yields one hex DER line out.

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.HexFormat;

public class JvmSigner {
    public static void main(String[] args) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = kpg.generateKeyPair();
        System.out.println(Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));
        System.out.flush();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        HexFormat hex = HexFormat.of();
        String line;
        while ((line = in.readLine()) != null) {
            Signature sig = Signature.getInstance("NONEwithECDSA");
            sig.initSign(kp.getPrivate());
            sig.update(hex.parseHex(line.trim()));
            System.out.println(hex.formatHex(sig.sign()));
            System.out.flush();
        }
    }
}
