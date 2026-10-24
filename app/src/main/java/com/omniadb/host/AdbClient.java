package com.omniadb.host;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.conscrypt.Conscrypt;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Date;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbStream;

/**
 * 本機 ADB 用戶端。
 *
 * 這是整個免 root 方案的核心：App 自己就是一台「ADB 主機」。
 * 先用配對碼跟手機自己的 adbd 完成一次 TLS 配對（一次性），取得自己的
 * ADB 金鑰後，之後就能在裝置上直接連 127.0.0.1 對 adbd 下指令，
 * 包含把 ADB 切換到固定的 TCP 埠。
 */
public class AdbClient {

    private static final String TAG = "OmniADB";

    private final Context dpContext;

    public AdbClient(Context context) {
        this.dpContext = Store.dp(context);
        try {
            Security.removeProvider("BC");
            Security.insertProviderAt(new BouncyCastleProvider(), 1);
            Security.insertProviderAt(Conscrypt.newProvider(), 2);
        } catch (Exception e) {
            Log.e(TAG, "初始化加密 provider 失敗", e);
        }
    }

    /** 一次性配對：host/port 來自「使用配對碼配對裝置」畫面。 */
    public boolean pair(String host, int port, String code) {
        SimpleAdbManager m = null;
        try {
            m = new SimpleAdbManager(dpContext);
            m.pair(host, port, code);
            Log.i(TAG, "配對成功 " + host + ":" + port);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "配對失敗", e);
            return false;
        } finally {
            closeQuietly(m);
        }
    }

    /** 測試是否能用既有金鑰連上指定埠。 */
    public boolean connect(String host, int port) {
        SimpleAdbManager m = null;
        try {
            m = new SimpleAdbManager(dpContext);
            m.connect(host, port);
            return true;
        } catch (Exception e) {
            Log.d(TAG, "連線 " + host + ":" + port + " 失敗：" + e.getMessage());
            return false;
        } finally {
            closeQuietly(m);
        }
    }

    /** 執行 shell 指令並取回輸出。 */
    public String shell(String host, int port, String command) throws Exception {
        SimpleAdbManager m = null;
        try {
            m = new SimpleAdbManager(dpContext);
            m.connect(host, port);
            return readStream(m, "shell:" + command);
        } finally {
            closeQuietly(m);
        }
    }

    /** 把 adbd 從目前的埠切換到 targetPort（固定埠的關鍵一步）。 */
    public boolean switchToPort(String host, int port, int targetPort) {
        SimpleAdbManager m = null;
        try {
            m = new SimpleAdbManager(dpContext);
            m.connect(host, port);
            Thread.sleep(200);
            try {
                readStream(m, "tcpip:" + targetPort);
            } catch (Exception e) {
                Log.d(TAG, "tcpip 串流結束：" + e.getMessage());
            }
            // adbd 會重啟，给它一点时间
            Thread.sleep(3000);
            Log.i(TAG, "已送出 tcpip:" + targetPort + "（經由 " + host + ":" + port + "）");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "切換埠失敗 " + host + ":" + port + " -> " + targetPort, e);
            return false;
        } finally {
            closeQuietly(m);
        }
    }

    private static String readStream(SimpleAdbManager m, String destination) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (AdbStream stream = m.openStream(destination);
             InputStream in = stream.openInputStream()) {
            byte[] buf = new byte[2048];
            int n;
            try {
                while ((n = in.read(buf)) != -1) {
                    sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                if (!"Stream closed.".equals(e.getMessage())) throw e;
            }
        }
        return sb.toString();
    }

    private static void closeQuietly(SimpleAdbManager m) {
        if (m == null) return;
        try {
            m.close();
        } catch (Exception ignored) {
        }
    }

    /** libadb 的連線管理器，負責金鑰的產生、保存與載入。 */
    private static class SimpleAdbManager extends AbsAdbConnectionManager {

        private final File keyFile;
        private final File pubKeyFile;
        private final File certFile;

        private PrivateKey privateKey;
        private PublicKey publicKey;
        private X509Certificate certificate;

        SimpleAdbManager(Context dpContext) throws Exception {
            setApi(Build.VERSION.SDK_INT);
            File dir = dpContext.getFilesDir();
            keyFile = new File(dir, "adb_key");
            pubKeyFile = new File(dir, "adb_key.pub");
            certFile = new File(dir, "adb_cert");
            loadOrGenerate();
        }

        private void loadOrGenerate() throws Exception {
            if (keyFile.exists() && pubKeyFile.exists() && certFile.exists()) {
                try {
                    KeyFactory kf = KeyFactory.getInstance("RSA");
                    privateKey = kf.generatePrivate(
                            new PKCS8EncodedKeySpec(readAll(keyFile)));
                    publicKey = kf.generatePublic(
                            new X509EncodedKeySpec(readAll(pubKeyFile)));
                    CertificateFactory cf = CertificateFactory.getInstance("X.509");
                    certificate = (X509Certificate) cf.generateCertificate(
                            new ByteArrayInputStream(readAll(certFile)));
                    return;
                } catch (Exception e) {
                    Log.e(TAG, "既有金鑰載入失敗，重新產生", e);
                }
            }
            generate();
        }

        private void generate() throws Exception {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048, new SecureRandom());
            KeyPair kp = gen.generateKeyPair();
            privateKey = kp.getPrivate();
            publicKey = kp.getPublic();
            certificate = selfSigned(kp);

            writeAll(keyFile, privateKey.getEncoded());
            writeAll(pubKeyFile, publicKey.getEncoded());
            writeAll(certFile, certificate.getEncoded());
        }

        private X509Certificate selfSigned(KeyPair kp) throws Exception {
            X500Name name = new X500Name("CN=AdbAlwaysOn");
            BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
            Date from = new Date(System.currentTimeMillis() - 24L * 60 * 60 * 1000);
            Date to = new Date(System.currentTimeMillis() + 3650L * 24 * 60 * 60 * 1000);
            SubjectPublicKeyInfo spki =
                    SubjectPublicKeyInfo.getInstance(kp.getPublic().getEncoded());

            X509v3CertificateBuilder builder =
                    new X509v3CertificateBuilder(name, serial, from, to, name, spki);
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                    .setProvider("BC")
                    .build(kp.getPrivate());
            X509CertificateHolder holder = builder.build(signer);
            return new JcaX509CertificateConverter()
                    .setProvider("BC")
                    .getCertificate(holder);
        }

        private static byte[] readAll(File f) throws IOException {
            byte[] out = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int off = 0;
                while (off < out.length) {
                    int n = in.read(out, off, out.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            return out;
        }

        private static void writeAll(File f, byte[] data) throws IOException {
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(data);
            }
        }

        @Override
        @NonNull
        protected PrivateKey getPrivateKey() {
            return privateKey;
        }

        @Override
        @NonNull
        protected Certificate getCertificate() {
            return certificate;
        }

        @Override
        @NonNull
        protected String getDeviceName() {
            return "OmniADB";
        }
    }
}
