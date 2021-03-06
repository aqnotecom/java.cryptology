package cwguide;

import static com.aqnote.shared.cryptology.cert.constant.BCConstant.JCE_PROVIDER;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.DERBMPString;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.oiw.OIWObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.Attribute;
import org.bouncycastle.asn1.pkcs.ContentInfo;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.operator.InputDecryptorProvider;
import org.bouncycastle.operator.OutputEncryptor;
import org.bouncycastle.operator.bc.BcDefaultDigestProvider;
import org.bouncycastle.pkcs.PKCS12PfxPdu;
import org.bouncycastle.pkcs.PKCS12PfxPduBuilder;
import org.bouncycastle.pkcs.PKCS12SafeBag;
import org.bouncycastle.pkcs.PKCS12SafeBagBuilder;
import org.bouncycastle.pkcs.PKCS12SafeBagFactory;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.bouncycastle.pkcs.bc.BcPKCS12MacCalculatorBuilderProvider;
import org.bouncycastle.pkcs.jcajce.JcaPKCS12SafeBagBuilder;
import org.bouncycastle.pkcs.jcajce.JcePKCS12MacCalculatorBuilder;
import org.bouncycastle.pkcs.jcajce.JcePKCSPBEInputDecryptorProviderBuilder;
import org.bouncycastle.pkcs.jcajce.JcePKCSPBEOutputEncryptorBuilder;
import org.bouncycastle.util.io.Streams;

import com.aqnote.shared.cryptology.AQProviderUtil;

public class JcePKCS12Example
{
    public static final String file = "/home/aqnote/output/id.p12";
    public static void main(String[] args)
        throws Exception
    {
        AQProviderUtil.addBCProvider();

        KeyStore credentials = JcaUtils.createCredentials();
        PrivateKey key = (PrivateKey)credentials.getKey(JcaUtils.END_ENTITY_ALIAS, JcaUtils.KEY_PASSWD);
        Certificate[] chain = credentials.getCertificateChain(JcaUtils.END_ENTITY_ALIAS);

        createPKCS12File(new FileOutputStream(file), key, chain);

        //
        // or alternately just load it up using a KeyStore
        //
        KeyStore pkcs12Store = KeyStore.getInstance("PKCS12", JCE_PROVIDER);

        pkcs12Store.load(new FileInputStream(file), JcaUtils.KEY_PASSWD);

        System.out.println("########## KeyStore Dump");

        for (Enumeration<?> en = pkcs12Store.aliases(); en.hasMoreElements();)
        {
            String alias = (String)en.nextElement();

            if (pkcs12Store.isCertificateEntry(alias))
            {
                System.out.println("Certificate Entry: " + alias + ", Subject: " + (((X509Certificate)pkcs12Store.getCertificate(alias)).getSubjectDN()));
            }
            else if (pkcs12Store.isKeyEntry(alias))
            {
                System.out.println("Key Entry: " + alias + ", Subject: " + (((X509Certificate)pkcs12Store.getCertificate(alias)).getSubjectDN()));
            }
        }

        System.out.println();
    }

    private static void createPKCS12File(OutputStream pfxOut, PrivateKey key, Certificate[] chain)
        throws Exception
    {
        PKCS12SafeBagBuilder taCertBagBuilder = new JcaPKCS12SafeBagBuilder((X509Certificate)chain[2]);

        taCertBagBuilder.addBagAttribute(PKCS12SafeBag.friendlyNameAttribute, new DERBMPString("Bouncy Primary Certificate"));

        PKCS12SafeBagBuilder caCertBagBuilder = new JcaPKCS12SafeBagBuilder((X509Certificate)chain[1]);

        caCertBagBuilder.addBagAttribute(PKCS12SafeBag.friendlyNameAttribute, new DERBMPString("Bouncy Intermediate Certificate"));

        JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();
        PKCS12SafeBagBuilder eeCertBagBuilder = new JcaPKCS12SafeBagBuilder((X509Certificate)chain[0]);

        eeCertBagBuilder.addBagAttribute(PKCS12SafeBag.friendlyNameAttribute, new DERBMPString("Eric's Key"));
        SubjectKeyIdentifier pubKeyId = extUtils.createSubjectKeyIdentifier(chain[0].getPublicKey());
        eeCertBagBuilder.addBagAttribute(PKCS12SafeBag.localKeyIdAttribute, pubKeyId);


        PKCS12PfxPduBuilder builder = new PKCS12PfxPduBuilder();

        OutputEncryptor oKeyEncryptor = new JcePKCSPBEOutputEncryptorBuilder(NISTObjectIdentifiers.id_aes256_CBC).setProvider(JCE_PROVIDER).build(JcaUtils.KEY_PASSWD);
        PKCS12SafeBagBuilder keyBagBuilder = new JcaPKCS12SafeBagBuilder(key, oKeyEncryptor);
        keyBagBuilder.addBagAttribute(PKCS12SafeBag.friendlyNameAttribute, new DERBMPString("Eric's Key"));
        keyBagBuilder.addBagAttribute(PKCS12SafeBag.localKeyIdAttribute, pubKeyId);
        builder.addData(keyBagBuilder.build());

        OutputEncryptor oCertEncryptor = new JcePKCSPBEOutputEncryptorBuilder(PKCSObjectIdentifiers.pbeWithSHAAnd40BitRC2_CBC).setProvider(JCE_PROVIDER).build(JcaUtils.KEY_PASSWD);
        builder.addEncryptedData(oCertEncryptor, new PKCS12SafeBag[]{eeCertBagBuilder.build(), caCertBagBuilder.build(), taCertBagBuilder.build()});

        PKCS12PfxPdu pfx = builder.build(new JcePKCS12MacCalculatorBuilder(OIWObjectIdentifiers.idSHA1), JcaUtils.KEY_PASSWD);

        // make sure we don't include indefinite length encoding
        pfxOut.write(pfx.getEncoded(ASN1Encoding.DL));

        pfxOut.close();
    }

    public static PKCS12PfxPdu readPKCS12File(InputStream pfxIn) throws Exception {
        PKCS12PfxPdu pfx = new PKCS12PfxPdu(Streams.readAll(pfxIn));

        if (!pfx.isMacValid(new BcPKCS12MacCalculatorBuilderProvider(BcDefaultDigestProvider.INSTANCE), JcaUtils.KEY_PASSWD))
        {
            System.err.println("PKCS#12 MAC test failed!");
        }

        ContentInfo[] infos = pfx.getContentInfos();

        Map<String, X509Certificate> certMap = new HashMap<String, X509Certificate>();
        Map<ASN1Encodable, X509Certificate> certKeyIds = new HashMap<ASN1Encodable, X509Certificate>();
        Map<String, PrivateKey> privKeyMap = new HashMap<String, PrivateKey>();
        Map<PrivateKey, ASN1Encodable> privKeyIds = new HashMap<PrivateKey, ASN1Encodable>();

        InputDecryptorProvider inputDecryptorProvider = new JcePKCSPBEInputDecryptorProviderBuilder()
                                                              .setProvider(JCE_PROVIDER).build(JcaUtils.KEY_PASSWD);
        JcaX509CertificateConverter  jcaConverter = new JcaX509CertificateConverter().setProvider(JCE_PROVIDER);

        for (int i = 0; i != infos.length; i++)
        {
            if (infos[i].getContentType().equals(PKCSObjectIdentifiers.encryptedData))
            {
                PKCS12SafeBagFactory dataFact = new PKCS12SafeBagFactory(infos[i], inputDecryptorProvider);

                PKCS12SafeBag[] bags = dataFact.getSafeBags();

                for (int b = 0; b != bags.length; b++)
                {
                    PKCS12SafeBag bag = bags[b];

                    X509CertificateHolder certHldr = (X509CertificateHolder)bag.getBagValue();
                    X509Certificate       cert = jcaConverter.getCertificate(certHldr);

                    Attribute[] attributes = bag.getAttributes();
                    for (int a = 0; a != attributes.length; a++)
                    {
                        Attribute attr = attributes[a];

                        if (attr.getAttrType().equals(PKCS12SafeBag.friendlyNameAttribute))
                        {
                            certMap.put(((DERBMPString)attr.getAttributeValues()[0]).getString(), cert);
                        }
                        else if (attr.getAttrType().equals(PKCS12SafeBag.localKeyIdAttribute))
                        {
                            certKeyIds.put(attr.getAttributeValues()[0], cert);
                        }
                    }
                }
            }
            else
            {
                PKCS12SafeBagFactory dataFact = new PKCS12SafeBagFactory(infos[i]);

                PKCS12SafeBag[] bags = dataFact.getSafeBags();

                PKCS8EncryptedPrivateKeyInfo encInfo = (PKCS8EncryptedPrivateKeyInfo)bags[0].getBagValue();
                PrivateKeyInfo info = encInfo.decryptPrivateKeyInfo(inputDecryptorProvider);

                KeyFactory keyFact = KeyFactory .getInstance(info.getPrivateKeyAlgorithm().getAlgorithm().getId(), JCE_PROVIDER);
                PrivateKey privKey = keyFact.generatePrivate(new PKCS8EncodedKeySpec(info.getEncoded()));

                Attribute[] attributes = bags[0].getAttributes();
                for (int a = 0; a != attributes.length; a++)
                {
                    Attribute attr = attributes[a];

                    if (attr.getAttrType().equals(PKCS12SafeBag.friendlyNameAttribute))
                    {
                        privKeyMap.put(((DERBMPString)attr.getAttributeValues()[0]).getString(), privKey);
                    }
                    else if (attr.getAttrType().equals(PKCS12SafeBag.localKeyIdAttribute))
                    {
                        privKeyIds.put(privKey, attr.getAttributeValues()[0]);
                    }
                }
            }
        }

        System.out.println("########## PFX Dump");
        for (Iterator<String> it = privKeyMap.keySet().iterator(); it.hasNext();)
        {
            String alias = (String)it.next();

            System.out.println("Key Entry: " + alias + ", Subject: " + (((X509Certificate)certKeyIds.get(privKeyIds.get(privKeyMap.get(alias)))).getSubjectDN()));
        }

        for (Iterator<String> it = certMap.keySet().iterator(); it.hasNext();)
        {
            String alias = (String)it.next();

            System.out.println("Certificate Entry: " + alias + ", Subject: " + (((X509Certificate)certMap.get(alias)).getSubjectDN()));
        }
        System.out.println();

        return pfx;
    }
}
