/* 
 * Copyright (c) 2007-2008, Hazel Ltd. All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at 
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hazelcast.nio;

import com.hazelcast.config.Config;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.impl.Build;
import static com.hazelcast.impl.Constants.IO.BYTE_BUFFER_SIZE;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.security.*;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.KeySpec;
import java.util.logging.Level;
import java.util.logging.Logger;

abstract class AbstractSelectionHandler implements SelectionHandler {

    protected static Logger logger = Logger.getLogger(AbstractSelectionHandler.class.getName());

    public static final int RECEIVE_SOCKET_BUFFER_SIZE = 32 * BYTE_BUFFER_SIZE;

    public static final int SEND_SOCKET_BUFFER_SIZE = 32 * BYTE_BUFFER_SIZE;

    public static final boolean DEBUG = Build.DEBUG;

    protected SocketChannel socketChannel;

    protected InSelector inSelector;

    protected OutSelector outSelector;

    protected Connection connection;

    protected SelectionKey sk = null;

    final static CipherBuilder cipherBuilder;

    static {
        NetworkConfig nc = Config.get().getNetworkConfig();
        if (nc.getSymmetricEncryptionConfig() != null && nc.getSymmetricEncryptionConfig().isEnabled()) {
             cipherBuilder = new SymmetricCipherBuilder();
        } else if (nc.getAsymmetricEncryptionConfig() != null && nc.getAsymmetricEncryptionConfig().isEnabled()) {
             cipherBuilder = new AsymmetricCipherBuilder();
        } else {
            cipherBuilder = null;
        }
        System.out.println("CIPHER BUILDER " + cipherBuilder);
    }

    public AbstractSelectionHandler(final Connection connection, boolean writer) {
        super();
        this.connection = connection;
        this.socketChannel = connection.getSocketChannel();
        this.inSelector = InSelector.get();
        this.outSelector = OutSelector.get();
    }

    protected void shutdown() {

    }

    final void handleSocketException(final Exception e) {
        if (DEBUG) {
            logger.log(Level.FINEST,
                    Thread.currentThread().getName() + " Closing Socket. cause:  ", e);
        }

        if (sk != null)
            sk.cancel();
        connection.close();
    }

    final void registerOp(final Selector selector, final int operation) {
        try {
            if (!connection.live())
                return;
            if (sk == null) {
                sk = socketChannel.register(selector, operation, this);
            } else {
                sk.interestOps(operation);
            }
        } catch (Exception e) {
            handleSocketException(e);
        }
    }

    interface CipherBuilder {
        Cipher getWriterCipher();

        Cipher getReaderCipher();

        boolean isAsymmetric();

    }

    static class AsymmetricCipherBuilder implements CipherBuilder {
        final String algorithm = "RSA/NONE/PKCS1PADDING";
        //        final String algorithm = "RSA/NONE/NoPADDING";
        KeyPair keyPair;
        final Cipher writer;
        final Cipher reader;


        AsymmetricCipherBuilder() {
            try {
                String provider = "org.bouncycastle.jce.provider.BouncyCastleProvider";
                Security.addProvider((Provider) Class.forName(provider).newInstance());
                keyPair = getKP();

            } catch (Exception e) {
                e.printStackTrace();
            }
            writer = create(true);
            reader = create(false);
        }

        private KeyPair getKP() throws Exception {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());

            // get user password and file input stream
            char[] password = "thestorepass".toCharArray();
            java.io.FileInputStream fis =
                    new java.io.FileInputStream("/Users/talipozturk/thekeystore");
            ks.load(fis, password);
            fis.close();

            // get my private key
            KeyStore.PrivateKeyEntry pkEntry = (KeyStore.PrivateKeyEntry)
                    ks.getEntry("local", new KeyStore.PasswordProtection("thekeypass".toCharArray()));
            PrivateKey myPrivateKey = pkEntry.getPrivateKey();

            PublicKey publicKey = pkEntry.getCertificate().getPublicKey();

            return new KeyPair(publicKey, myPrivateKey);
        }

        private synchronized Cipher create(boolean encryptMode) {
            try {
                Cipher cipher = Cipher.getInstance(algorithm);

                if (encryptMode) {
                    cipher.init(Cipher.ENCRYPT_MODE, keyPair.getPublic());
                } else {
                    cipher.init(Cipher.DECRYPT_MODE, keyPair.getPrivate());
                }
                return cipher;
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(0);
            }
            return null;
        }

        public Cipher getWriterCipher() {
            return writer;
        }

        public Cipher getReaderCipher() {
            return reader;
        }

        public boolean isAsymmetric() {
            return true;
        }
    }

    static class SymmetricCipherBuilder implements CipherBuilder {
        // 8-byte Salt
        final byte[] salt = {
                (byte) 0xA9, (byte) 0x9B, (byte) 0xC8, (byte) 0x32,
                (byte) 0x56, (byte) 0x35, (byte) 0xE3, (byte) 0x03
        };
        final String passPhrase = "hazelcast";
        final Cipher writer;
        final Cipher reader;

        SymmetricCipherBuilder() {
            writer = create(true);
            reader = create(false);
        }

        public Cipher create(boolean encryptMode) {
            try {

                int iterationCount = 32;
                // Create the key
                KeySpec keySpec = new PBEKeySpec(passPhrase.toCharArray(), salt, iterationCount);
                SecretKey key = SecretKeyFactory.getInstance(
                        "PBEWithMD5AndDES").generateSecret(keySpec);
                Cipher cipher = Cipher.getInstance(key.getAlgorithm());

                // Prepare the parameter to the ciphers
                AlgorithmParameterSpec paramSpec = new PBEParameterSpec(salt, iterationCount);

                // Create the ciphers
                cipher.init((encryptMode) ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, key, paramSpec);

                return cipher;
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(0);
            }
            return null;
        }

        public Cipher getWriterCipher() {
            return writer;
        }

        public Cipher getReaderCipher() {
            return reader;
        }

        public boolean isAsymmetric() {
            return false;
        }
    }

}
