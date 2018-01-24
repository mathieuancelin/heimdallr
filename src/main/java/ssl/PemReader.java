/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package ssl;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

public class PemReader {

    public static KeyPair keyPairFromPem(String path) throws Exception {
        PEMParser parser = new PEMParser(new InputStreamReader(new FileInputStream(new File(path))));

        // Load the key object
        Object privatekey = parser.readObject();

        // Check to see if the object returned is an encrypted key pair
        if (privatekey instanceof PEMEncryptedKeyPair) {
            privatekey = ((PEMEncryptedKeyPair) privatekey).decryptKeyPair(new JcePEMDecryptorProviderBuilder().build("xxxxxxxx".toCharArray()));
        }

        // Cast to a PEMKeyPair
        PEMKeyPair pair = (PEMKeyPair) privatekey;

        // Get the encoded objects ready for conversion to Java objects
        byte[] encodedPublicKey = pair.getPublicKeyInfo().getEncoded();
        byte[] encodedPrivateKey = pair.getPrivateKeyInfo().getEncoded();

        // Now convert to Java objects
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");

        X509EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(encodedPublicKey);
        PublicKey publicKey = keyFactory.generatePublic(publicKeySpec);

        PKCS8EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(encodedPrivateKey);
        PrivateKey privateKey = keyFactory.generatePrivate(privateKeySpec);

        return new KeyPair(publicKey, privateKey);
    }

    public static X509Certificate certificateFromCrt(String path) throws Exception {
        PEMParser parser = new PEMParser(new InputStreamReader(new FileInputStream(new File(path))));

        X509CertificateHolder obj = (X509CertificateHolder) parser.readObject();
        return new JcaX509CertificateConverter().setProvider("BC").getCertificate(obj);
    }

    public static KeyStore loadKeystore(String alias, KeyPair pair, X509Certificate cert, String passphrase) throws Exception {
        KeyStore store = KeyStore.getInstance("JKS");
        store.load(null);
        store.setKeyEntry(alias, pair.getPrivate(), passphrase.toCharArray(),	new java.security.cert.Certificate[] { cert });
        return store;
    }
}