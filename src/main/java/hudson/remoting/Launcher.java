/*
 * The MIT License
 *
 * Copyright (c) 2004-2010, Sun Microsystems, Inc., Kohsuke Kawaguchi
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.remoting;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.remoting.Channel.Mode;
import org.jenkinsci.remoting.engine.WorkDirManager;
import org.jenkinsci.remoting.util.PathUtils;
import org.jenkinsci.remoting.util.https.NoCheckHostnameVerifier;
import org.jenkinsci.remoting.util.https.NoCheckTrustManager;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.Console;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivilegedActionException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry point for running a {@link Channel}. This is the main method of the agent JVM.
 *
 * <p>
 * This class also defines several methods for
 * starting a channel on a fresh JVM.
 *
 * @author Kohsuke Kawaguchi
 */
@SuppressFBWarnings(value = "DM_EXIT", justification = "This class is runnable. It is eligible to exit in the case of wrong params")
public class Launcher {
    public Mode mode = Mode.BINARY;

    // no-op, but left for backward compatibility
    @Option(name="-ping")
    public boolean ping = true;

    /**
     * Specifies a destination for error logs.
     * If specified, this option overrides the default destination within {@link #workDir}.
     * If both this options and {@link #workDir} is not set, the log will not be generated.
     */
    @Option(name="-agentLog", usage="Local agent error log destination (overrides workDir)")
    @CheckForNull
    public File agentLog = null;

    @Option(name="-text",usage="encode communication with the controller with base64. " +
            "Useful for running agent over 8-bit unsafe protocol like telnet")
    public void setTextMode(boolean b) {
        mode = b?Mode.TEXT:Mode.BINARY;
        System.out.println("Running in "+mode.name().toLowerCase(Locale.ENGLISH)+" mode");
    }

    @Option(name="-jnlpUrl",usage="instead of talking to the controller via stdin/stdout, " +
            "emulate a JNLP client by making a TCP connection to the controller. " +
            "Connection parameters are obtained by parsing the JNLP file.")
    public URL agentJnlpURL = null;

    @Option(name="-jnlpCredentials",metaVar="USER:PASSWORD",usage="HTTP BASIC AUTH header to pass in for making HTTP requests.")
    public String agentJnlpCredentials = null;

    @Option(name="-secret", metaVar="HEX_SECRET", usage="Agent connection secret to use instead of -jnlpCredentials.")
    public String secret;

    @Option(name="-proxyCredentials",metaVar="USER:PASSWORD",usage="HTTP BASIC AUTH header to pass in for making HTTP authenticated proxy requests.")
    public String proxyCredentials = null;

    @Option(name="-cp",aliases="-classpath",metaVar="PATH",
            usage="add the given classpath elements to the system classloader. (DEPRECATED)")
    @SuppressFBWarnings(value = "PATH_TRAVERSAL_IN", justification = "Parameter supplied by user / administrator.")
    public void addClasspath(String pathList) throws Exception {
        LOGGER.warning("[JENKINS-64831] Passing -cp to hudson.remoting.Launcher is deprecated and may not work at all on Java 9+");
        Method $addURL = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
        $addURL.setAccessible(true);

        for(String token : pathList.split(File.pathSeparator))
            $addURL.invoke(ClassLoader.getSystemClassLoader(),new File(token).toURI().toURL());

        // fix up the system.class.path to pretend that those jar files
        // are given through CLASSPATH or something.
        // some tools like JAX-WS RI and Hadoop relies on this.
        System.setProperty("java.class.path",System.getProperty("java.class.path")+File.pathSeparatorChar+pathList);
    }

    @Option(name="-tcp",usage="instead of talking to the controller via stdin/stdout, " +
            "listens to a random local port, write that port number to the given file, " +
            "then wait for the controller to connect to that port.")
    public File tcpPortFile=null;


    @Option(name="-auth",metaVar="user:pass",usage="If your Jenkins is security-enabled, specify a valid user name and password.")
    public String auth = null;

    /**
     * @since 2.24
     */
    @CheckForNull
    @Option(name="-jar-cache",metaVar="DIR",usage="Cache directory that stores jar files sent from the controller")
    public File jarCache = null;

    /**
     * Specified location of the property file with JUL settings.
     * @since 3.8
     */
    @CheckForNull
    @Option(name="-loggingConfig",usage="Path to the property file with java.util.logging settings")
    public File loggingConfigFilePath = null;

    @Option(name = "-cert",
            usage = "Specify additional X.509 encoded PEM certificates to trust when connecting to Jenkins " +
                    "root URLs. If starting with @ then the remainder is assumed to be the name of the " +
                    "certificate file to read.", forbids = "-noCertificateCheck")
    public List<String> candidateCertificates;

    /**
     * Disables HTTPs Certificate validation of the server when using {@link org.jenkinsci.remoting.engine.JnlpAgentEndpointResolver}.
     * This option is managed by the {@code -noCertificateCheck} option.
     */
    private boolean noCertificateCheck = false;

    public InetSocketAddress connectionTarget = null;

    @Option(name="-connectTo",usage="make a TCP connection to the given host and port, then start communication.",metaVar="HOST:PORT")
    public void setConnectTo(String target) {
        String[] tokens = target.split(":");
        if(tokens.length!=2) {
            System.err.println("Illegal parameter: "+target);
            System.exit(1);
        }
        connectionTarget = new InetSocketAddress(tokens[0],Integer.parseInt(tokens[1]));
    }

    /**
     * Bypass HTTPS security check by using free-for-all trust manager.
     *
     * @param ignored
     *      This is ignored.
     */
    @Option(name="-noCertificateCheck", forbids = "-cert")
    public void setNoCertificateCheck(boolean ignored) throws NoSuchAlgorithmException, KeyManagementException {
        System.out.println("Skipping HTTPS certificate checks altogether. Note that this is not secure at all.");

        this.noCertificateCheck = true;
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{new NoCheckTrustManager()}, new java.security.SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
        // bypass host name check, too.
        HttpsURLConnection.setDefaultHostnameVerifier(new NoCheckHostnameVerifier());
    }

    @Option(name="-noReconnect",usage="Doesn't try to reconnect when a communication fail, and exit instead")
    public boolean noReconnect = false;

    @Option(name = "-noKeepAlive",
            usage = "Disable TCP socket keep alive on connection to the controller.")
    public boolean noKeepAlive = false;

    /**
     * Specifies a default working directory of the remoting instance.
     * If specified, this directory will be used to store logs, JAR cache, etc.
     * <p>
     * In order to retain compatibility, the option is disabled by default.
     * <p>
     * Jenkins specifics: This working directory is expected to be equal to the agent root specified in Jenkins configuration.
     * @since 3.8
     */
    @Option(name = "-workDir",
            usage = "Declares the working directory of the remoting instance (stores cache and logs by default)")
    @CheckForNull
    public File workDir = null;

    /**
     * Specifies a directory within {@link #workDir}, which stores all the remoting-internal files.
     * <p>
     * This option is not expected to be used frequently, but it allows remoting users to specify a custom
     * storage directory if the default {@code remoting} directory is consumed by other stuff.
     * @since 3.8
     */
    @Option(name = "-internalDir",
            usage = "Specifies a name of the internal files within a working directory ('remoting' by default)",
            depends = "-workDir")
    @NonNull
    public String internalDir = WorkDirManager.DirType.INTERNAL_DIR.getDefaultLocation();

    /**
     * Fail the initialization if the workDir or internalDir are missing.
     * This option presumes that the workspace structure gets initialized previously in order to ensure that we do not start up with a borked instance
     * (e.g. if a filesystem mount gets disconnected).
     * @since 3.8
     */
    @Option(name = "-failIfWorkDirIsMissing",
            usage = "Fails the initialization if the requested workDir or internalDir are missing ('false' by default)",
            depends = "-workDir")
    public boolean failIfWorkDirIsMissing = WorkDirManager.DEFAULT_FAIL_IF_WORKDIR_IS_MISSING;
    
    /**
     * Shows help message and then exits
     * @since 3.36
     */
    @Option(name="-help",usage="Show this help message")
    public boolean showHelp = false;

    /**
     * Shows version information and then exits
     * @since 3.36
     */
    @Option(name="-version",usage="Shows the version of the remoting jar and then exits")
    public boolean showVersion = false;


    public static void main(String... args) throws Exception {
        Launcher launcher = new Launcher();
        CmdLineParser parser = new CmdLineParser(launcher);
        try {
            parser.parseArgument(args);
            if (launcher.showHelp && !launcher.showVersion) {
                parser.printUsage(System.out);
                return;
            }
            launcher.run();
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            System.err.println("java -jar agent.jar [options...]");
            parser.printUsage(System.err);
            System.err.println();
        }
    }

    @SuppressFBWarnings(value = "DM_DEFAULT_ENCODING", justification = "log file, just like console output, should be in platform default encoding")
    public void run() throws Exception {
        if (showVersion) {
            String version = Util.getVersion();
            if(version != null) {
                System.out.println(version);
            }
            return;
        }

        // Create and verify working directory and logging
        // TODO: The pass-through for the JNLP mode has been added in JENKINS-39817. But we still need to keep this parameter in
        // consideration for other modes (TcpServer, TcpClient, etc.) to retain the legacy behavior.
        // On the other hand, in such case there is no need to invoke WorkDirManager and handle the double initialization logic
        final WorkDirManager workDirManager = WorkDirManager.getInstance();
        final Path internalDirPath = workDirManager.initializeWorkDir(workDir, internalDir, failIfWorkDirIsMissing);
        if (agentLog != null) {
            workDirManager.disable(WorkDirManager.DirType.LOGS_DIR);
        }
        if (loggingConfigFilePath != null) {
            workDirManager.setLoggingConfig(loggingConfigFilePath);
        }
        workDirManager.setupLogging(internalDirPath, agentLog != null ? PathUtils.fileToPath(agentLog) : null);

        if(auth!=null) {
            final int idx = auth.indexOf(':');
            if(idx<0)   throw new CmdLineException(null, "No ':' in the -auth option", null);
            Authenticator.setDefault(new Authenticator() {
                @Override public PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(auth.substring(0,idx), auth.substring(idx+1).toCharArray());
                }
            });
        }
        if (candidateCertificates != null && !candidateCertificates.isEmpty()) {
            HttpsURLConnection.setDefaultSSLSocketFactory(getSSLSocketFactory());
        }
        if(connectionTarget!=null) {
            runAsTcpClient();
        } else
        if(agentJnlpURL !=null) {
            List<String> jnlpArgs = parseJnlpArguments();
            if (jarCache != null) {
              jnlpArgs.add("-jar-cache");
              jnlpArgs.add(jarCache.getPath());
            }
            if (this.noReconnect) {
                jnlpArgs.add("-noreconnect");
            }
            if (this.noKeepAlive) {
                jnlpArgs.add("-noKeepAlive");
            }
            if (agentLog != null) {
                jnlpArgs.add("-agentLog");
                jnlpArgs.add(agentLog.getPath());
            }
            if (loggingConfigFilePath != null) {
                jnlpArgs.add("-loggingConfig");
                jnlpArgs.add(loggingConfigFilePath.getAbsolutePath());
            }
            if (this.workDir != null) {
                jnlpArgs.add("-workDir");
                jnlpArgs.add(workDir.getAbsolutePath());
                jnlpArgs.add("-internalDir");
                jnlpArgs.add(internalDir);
                if (failIfWorkDirIsMissing) {
                    jnlpArgs.add("-failIfWorkDirIsMissing");
                }
            }
            if (candidateCertificates != null && !candidateCertificates.isEmpty()) {
                for (String c: candidateCertificates) {
                    jnlpArgs.add("-cert");
                    jnlpArgs.add(c);
                }
            }
            if (noCertificateCheck) {
                // Generally it is not required since the default settings have been changed anyway.
                // But we set it up just in case there are overrides somewhere in the logic
                jnlpArgs.add("-disableHttpsCertValidation");
            }
            try {
                hudson.remoting.jnlp.Main._main(jnlpArgs.toArray(new String[0]));
            } catch (CmdLineException e) {
                System.err.println("JNLP file "+ agentJnlpURL +" has invalid arguments: "+jnlpArgs);
                System.err.println("Most likely a configuration error in the controller");
                System.err.println(e.getMessage());
                System.exit(1);
            }
        } else
        if(tcpPortFile!=null) {
            runAsTcpServer();
        } else {
            runWithStdinStdout();
        }
        System.exit(0);
    }

    @CheckForNull
    @SuppressFBWarnings(value = "PATH_TRAVERSAL_IN", justification = "Parameter supplied by user / administrator.")
    private SSLSocketFactory getSSLSocketFactory()
            throws PrivilegedActionException, KeyStoreException, NoSuchProviderException, CertificateException,
            NoSuchAlgorithmException, IOException, KeyManagementException {
        SSLSocketFactory sslSocketFactory = null;
        if (candidateCertificates != null && !candidateCertificates.isEmpty()) {
            CertificateFactory factory;
            try {
                factory = CertificateFactory.getInstance("X.509");
            } catch (CertificateException e) {
                throw new IllegalStateException("Java platform specification mandates support for X.509", e);
            }
            KeyStore keyStore = Engine.getCacertsKeyStore();
            // load the keystore
            keyStore.load(null, null);
            int i = 0;
            for (String certOrAtFilename : candidateCertificates) {
                certOrAtFilename = certOrAtFilename.trim();
                byte[] cert;
                if (certOrAtFilename.startsWith("@")) {
                    File file = new File(certOrAtFilename.substring(1));
                    long length;
                    if (file.isFile()
                            && (length = file.length()) < 65536
                            && length > "-----BEGIN CERTIFICATE-----\n-----END CERTIFICATE-----".length()) {
                        try (FileInputStream fis = new FileInputStream(file)) {
                            // we do basic size validation, if there are x509 certificates that have a PEM encoding
                            // larger
                            // than 64kb we can revisit the upper bound.
                            cert = new byte[(int) length];
                                int read = fis.read(cert);
                                if (cert.length != read) {
                                    LOGGER.log(Level.WARNING, "Only read {0} bytes from {1}, expected to read {2}",
                                            new Object[]{read, file, cert.length});
                                    // skip it
                                    continue;
                                }
                        } catch (IOException e) {
                            LOGGER.log(Level.WARNING, e, () -> "Could not read certificate from " + file);
                            continue;
                        }
                    } else {
                        if (file.isFile()) {
                            LOGGER.log(Level.WARNING,
                                    "Could not read certificate from {0}. File size is not within " +
                                            "the expected range for a PEM encoded X.509 certificate",
                                    file.getAbsolutePath());
                        } else {
                            LOGGER.log(Level.WARNING, "Could not read certificate from {0}. File not found",
                                    file.getAbsolutePath());
                        }
                        continue;
                    }
                } else {
                    cert = certOrAtFilename.getBytes(StandardCharsets.US_ASCII);
                }
                try {
                    keyStore.setCertificateEntry(String.format("alias-%d", i++),
                            factory.generateCertificate(new ByteArrayInputStream(cert)));
                } catch (ClassCastException e) {
                    LOGGER.log(Level.WARNING, "Expected X.509 certificate from " + certOrAtFilename, e);
                } catch (CertificateException e) {
                    LOGGER.log(Level.WARNING, "Could not parse X.509 certificate from " + certOrAtFilename, e);
                }
            }
            // prepare the trust manager
            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(keyStore);
            // prepare the SSL context
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustManagerFactory.getTrustManagers(), null);
            // now we have our custom socket factory
            sslSocketFactory = ctx.getSocketFactory();
        }
        return sslSocketFactory;
    }

    /**
     * Parses the connection arguments from JNLP file given in the URL.
     */
    @SuppressFBWarnings(value = {"CIPHER_INTEGRITY", "STATIC_IV"}, justification = "Integrity not needed here. IV used for decryption only, loaded from encryptor.")
    public List<String> parseJnlpArguments() throws ParserConfigurationException, SAXException, IOException, InterruptedException {
        if (secret != null) {
            agentJnlpURL = new URL(agentJnlpURL + "?encrypt=true");
            if (agentJnlpCredentials != null) {
                throw new IOException("-jnlpCredentials and -secret are mutually exclusive");
            }
        }
        while (true) {
            URLConnection con = null;
            try {
                con = Util.openURLConnection(agentJnlpURL);
                if (con instanceof HttpURLConnection) {
                    HttpURLConnection http = (HttpURLConnection) con;
                    if  (agentJnlpCredentials != null) {
                        String userPassword = agentJnlpCredentials;
                        String encoding = Base64.getEncoder().encodeToString(userPassword.getBytes(StandardCharsets.UTF_8));
                        http.setRequestProperty("Authorization", "Basic " + encoding);
                    }
                    if (System.getProperty("proxyCredentials", proxyCredentials) != null) {
                        String encoding = Base64.getEncoder().encodeToString(System.getProperty("proxyCredentials", proxyCredentials).getBytes(StandardCharsets.UTF_8));
                        http.setRequestProperty("Proxy-Authorization", "Basic " + encoding);
                    }
                }
                con.connect();

                if (con instanceof HttpURLConnection) {
                    HttpURLConnection http = (HttpURLConnection) con;
                    if(http.getResponseCode()>=400)
                        // got the error code. report that (such as 401)
                        throw new IOException("Failed to load "+ agentJnlpURL +": "+http.getResponseCode()+" "+http.getResponseMessage());
                }

                Document dom;

                // check if this URL points to a .jnlp file
                String contentType = con.getHeaderField("Content-Type");
                String expectedContentType = secret == null ? "application/x-java-jnlp-file" : "application/octet-stream";
                InputStream input = con.getInputStream();
                if (secret != null) {
                    byte[] payload = input.readAllBytes();
                    // the first 16 bytes (128bit) are initialization vector

                    try {
                        Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
                        cipher.init(Cipher.DECRYPT_MODE,
                                new SecretKeySpec(fromHexString(secret.substring(0, Math.min(secret.length(), 32))), "AES"),
                                new IvParameterSpec(payload,0,16));
                        byte[] decrypted = cipher.doFinal(payload,16,payload.length-16);
                        input = new ByteArrayInputStream(decrypted);
                    } catch (GeneralSecurityException x) {
                        throw new IOException("Failed to decrypt the JNLP file. Invalid secret key?", x);
                    }
                }
                if(contentType==null || !contentType.startsWith(expectedContentType)) {
                    // load DOM anyway, but if it fails to parse, that's probably because this is not an XML file to begin with.
                    try {
                        dom = loadDom(input);
                    } catch (SAXException | IOException e) {
                        throw new IOException(agentJnlpURL +" doesn't look like a JNLP file; content type was "+contentType);
                    }
                } else {
                    dom = loadDom(input);
                }

                // exec into the JNLP launcher, to fetch the connection parameter through JNLP.
                NodeList argElements = dom.getElementsByTagName("argument");
                List<String> jnlpArgs = new ArrayList<>();
                for( int i=0; i<argElements.getLength(); i++ )
                        jnlpArgs.add(argElements.item(i).getTextContent());
                if (agentJnlpCredentials != null) {
                    jnlpArgs.add("-credentials");
                    jnlpArgs.add(agentJnlpCredentials);
                }
                return jnlpArgs;
            } catch (SSLHandshakeException e) {
                if(e.getMessage().contains("PKIX path building failed")) {
                    // invalid SSL certificate. One reason this happens is when the certificate is self-signed
                    throw new IOException("Failed to validate a server certificate. If you are using a self-signed certificate, you can use the -noCertificateCheck option to bypass this check.", e);
                } else
                    throw e;
            } catch (IOException e) {
                if (this.noReconnect)
                    throw new IOException("Failed to obtain " + agentJnlpURL, e);

                System.err.println("Failed to obtain "+ agentJnlpURL);
                e.printStackTrace(System.err);
                System.err.println("Waiting 10 seconds before retry");
                // TODO refactor various sleep statements into a common method
                TimeUnit.SECONDS.sleep(10);
                // retry
            } finally {
                if (con instanceof HttpURLConnection) {
                    HttpURLConnection http = (HttpURLConnection) con;
                    http.disconnect();
                }
            }
        }
    }

    // from hudson.Util
    private static byte[] fromHexString(String data) {
        byte[] r = new byte[data.length() / 2];
        for (int i = 0; i < data.length(); i += 2)
            r[i / 2] = (byte) Integer.parseInt(data.substring(i, i + 2), 16);
        return r;
    }

    static Document loadDom(InputStream is) throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(is);
    }

    /**
     * Listens on an ephemeral port, record that port number in a port file,
     * then accepts one TCP connection.
     */
    @Deprecated
    @SuppressFBWarnings(value = {"UNENCRYPTED_SERVER_SOCKET", "DM_DEFAULT_ENCODING", "ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD"}, justification = "This is an old, insecure mechanism that should be removed. port number file should be in platform default encoding. Laucher instance is created only once.")
    private void runAsTcpServer() throws IOException, InterruptedException {
        // accept just one connection and that's it.
        // when we are done, remove the port file to avoid stale port file
        Socket s;
        try (ServerSocket ss = new ServerSocket(0,1)) {
            // if no one connects for too long, assume something went wrong
            // and avoid hanging forever
            ss.setSoTimeout(30*1000);

            // write a port file to report the port number
            try (FileWriter w = new FileWriter(tcpPortFile)) {
                w.write(String.valueOf(ss.getLocalPort()));
            }
            s = ss.accept();
        } finally {
            boolean deleted = tcpPortFile.delete();
            if (!deleted) {
                LOGGER.log(Level.WARNING, "Cannot delete the temporary TCP port file {0}", tcpPortFile);
            }
        }

        Launcher.communicationProtocolName = "TCP (remote: server)";
        runOnSocket(s);
    }

    private void runOnSocket(Socket s) throws IOException, InterruptedException {
        // this prevents a connection from silently terminated by the router in between or the other peer
        // and that goes without unnoticed. However, the time out is often very long (for example 2 hours
        // by default in Linux) that this alone is enough to prevent that.
        s.setKeepAlive(true);
        // we take care of buffering on our own
        s.setTcpNoDelay(true);
        main(new BufferedInputStream(SocketChannelStream.in(s)),
             new BufferedOutputStream(SocketChannelStream.out(s)), mode,ping,
             jarCache != null ? new FileSystemJarCache(jarCache,true) : null);
    }

    /**
     * Connects to the given TCP port and then start running
     */
    @SuppressFBWarnings(value = {"UNENCRYPTED_SOCKET", "ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD"}, justification = "This implements an old, insecure connection mechanism. Laucher instance is created only once.")
    @Deprecated
    private void runAsTcpClient() throws IOException, InterruptedException {
        // if no one connects for too long, assume something went wrong
        // and avoid hanging forever
        Socket s = new Socket(connectionTarget.getAddress(),connectionTarget.getPort());

        Launcher.communicationProtocolName = "TCP (remote: client)";
        runOnSocket(s);
    }

    @SuppressFBWarnings(value = {"ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD", "DMI_RANDOM_USED_ONLY_ONCE"}, justification = "Laucher instance is created only once.")
    private void runWithStdinStdout() throws IOException, InterruptedException {
        // use stdin/stdout for channel communication
        ttyCheck();

        if (isWindows()) {
            /*
                To prevent the dead lock between GetFileType from _ioinit in C runtime and blocking read that ChannelReaderThread
                would do on stdin, load the crypto DLL first.

                This is a band-aid solution to the problem. Still searching for more fundamental fix.

                02f1e750 7c90d99a ntdll!KiFastSystemCallRet
                02f1e754 7c810f63 ntdll!NtQueryVolumeInformationFile+0xc
                02f1e784 77c2c9f9 kernel32!GetFileType+0x7e
                02f1e7e8 77c1f01d msvcrt!_ioinit+0x19f
                02f1e88c 7c90118a msvcrt!__CRTDLL_INIT+0xac
                02f1e8ac 7c91c4fa ntdll!LdrpCallInitRoutine+0x14
                02f1e9b4 7c916371 ntdll!LdrpRunInitializeRoutines+0x344
                02f1ec60 7c9164d3 ntdll!LdrpLoadDll+0x3e5
                02f1ef08 7c801bbd ntdll!LdrLoadDll+0x230
                02f1ef70 7c801d72 kernel32!LoadLibraryExW+0x18e
                02f1ef84 7c801da8 kernel32!LoadLibraryExA+0x1f
                02f1efa0 77de8830 kernel32!LoadLibraryA+0x94
                02f1f05c 6d3eb1be ADVAPI32!CryptAcquireContextA+0x512
                WARNING: Stack unwind information not available. Following frames may be wrong.
                02f1f13c 6d99c844 java_6d3e0000!Java_sun_security_provider_NativeSeedGenerator_nativeGenerateSeed+0x6e

                see http://weblogs.java.net/blog/kohsuke/archive/2009/09/28/reading-stdin-may-cause-your-jvm-hang
                for more details
             */
            new SecureRandom().nextBoolean();
        }

        // this will prevent programs from accidentally writing to System.out
        // and messing up the stream.
        OutputStream os = new StandardOutputStream();
        System.setOut(System.err);

        Launcher.communicationProtocolName = "Standard in/out";
        // System.in/out appear to be already buffered (at least that was the case in Linux and Windows as of Java6)
        // so we are not going to double-buffer these.
        main(System.in, os, mode, ping, jarCache != null ? new FileSystemJarCache(jarCache,true) : null);
    }

    /**
     * Checks if there is any {@link java.io.Console Console} object associated with JVM.
     * If yes, prints a warning to STDOUT.
     */
    private static void ttyCheck() {
        final Console console = System.console();
        if(console != null) {
            // we seem to be running from interactive console. issue a warning.
            // but since this diagnosis could be wrong, go on and do what we normally do anyway. Don't exit.
            System.out.println(
                    "WARNING: Are you running agent from an interactive console?\n" +
                            "If so, you are probably using it incorrectly.\n" +
                            "See https://wiki.jenkins.io/display/JENKINS/Launching+agent+from+console");
        }
    }

    public static void main(InputStream is, OutputStream os) throws IOException, InterruptedException {
        main(is,os,Mode.BINARY);
    }

    public static void main(InputStream is, OutputStream os, Mode mode) throws IOException, InterruptedException {
        main(is,os,mode,false);
    }

    /**
     * @deprecated
     *      Use {@link #main(InputStream, OutputStream, Mode, boolean, JarCache)}
     */
    @Deprecated
    public static void main(InputStream is, OutputStream os, Mode mode, boolean performPing) throws IOException, InterruptedException {
        main(is, os, mode, performPing, null);
    }
    /**
     *
     * @param cache JAR cache to be used.
     *              If {@code null}, a default value will be used.
     * @since 2.24
     */
    public static void main(InputStream is, OutputStream os, Mode mode, boolean performPing, @CheckForNull JarCache cache) throws IOException, InterruptedException {
        ExecutorService executor = Executors.newCachedThreadPool();
        ChannelBuilder cb = new ChannelBuilder("channel", executor)
                .withMode(mode)
                .withJarCacheOrDefault(cache);

        // expose StandardOutputStream as a channel property, which is a better way to make this available
        // to the user of Channel than Channel#getUnderlyingOutput()
        if (os instanceof StandardOutputStream)
            cb.withProperty(StandardOutputStream.class,os);

        Channel channel = cb.build(is, os);
        System.err.println("channel started");

        // Both settings are available since remoting-2.0
        long timeout = 1000 * Long.parseLong(
                System.getProperty("hudson.remoting.Launcher.pingTimeoutSec", "240")),
             interval = 1000 * Long.parseLong(
                System.getProperty("hudson.remoting.Launcher.pingIntervalSec", /* was "600" but this duplicates ChannelPinger */ "0"));
        Logger.getLogger(PingThread.class.getName()).log(Level.FINE, "performPing={0} timeout={1} interval={2}", new Object[] {performPing, timeout, interval});
        if (performPing && timeout > 0 && interval > 0) {
            new PingThread(channel, timeout, interval) {
                @Deprecated
                @Override
                protected void onDead() {
                    System.err.println("Ping failed. Terminating");
                    System.exit(-1);
                }
                @Override
                @SuppressFBWarnings(value = "INFORMATION_EXPOSURE_THROUGH_AN_ERROR_MESSAGE", justification = "Prints the agent-side message to the agent log before exiting.")
                protected void onDead(Throwable cause) {
                    System.err.println("Ping failed. Terminating");
                    cause.printStackTrace();
                    System.exit(-1);
                }
            }.start();
        }
        channel.join();
        System.err.println("channel stopped");
    }

    public static boolean isWindows() {
        return File.pathSeparatorChar==';';
    }

    /**
     * Get the name of the communication protocol used in the Launcher.
     * When the channel is established by an Engine instance (that is, using JNLP),
     * use {@link Engine#getProtocolName()} instead.
     *
     * @return the communication protocol name.
     * @since 4.8
     */
    public static String getCommunicationProtocolName() {
        return Launcher.communicationProtocolName;
    }

    private static String computeVersion() {
        Properties props = new Properties();
        InputStream is = Launcher.class.getResourceAsStream(JENKINS_VERSION_PROP_FILE);
        if (is == null) {
            LOGGER.log(Level.FINE, "Cannot locate the {0} resource file. Hudson/Jenkins version is unknown",
                    JENKINS_VERSION_PROP_FILE);
            return UNKNOWN_JENKINS_VERSION_STR;
        }

        try {
            props.load(is);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            closeWithLogOnly(is, JENKINS_VERSION_PROP_FILE);
        }
        return props.getProperty("version", UNKNOWN_JENKINS_VERSION_STR);
    }

    private static void closeWithLogOnly(Closeable stream, String name) {
        try {
            stream.close();
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "Cannot close the resource file " + name, ex);
        }
    }

    private static String communicationProtocolName;

    /**
     * Version number of Hudson this agent.jar is from.
     */
    public static final String VERSION = computeVersion();

    private static final String JENKINS_VERSION_PROP_FILE = "hudson-version.properties";
    private static final String UNKNOWN_JENKINS_VERSION_STR = "?";

    private static final Logger LOGGER = Logger.getLogger(Launcher.class.getName());
}
