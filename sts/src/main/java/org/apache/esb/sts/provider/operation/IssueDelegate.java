package org.apache.esb.sts.provider.operation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableEntryException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.xml.bind.JAXBElement;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.keyinfo.X509Data;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.esb.sts.provider.ProviderPasswordCallback;
import org.apache.esb.sts.provider.STSException;
import org.apache.esb.sts.provider.SecurityTokenServiceImpl;
import org.joda.time.DateTime;
import org.oasis_open.docs.ws_sx.ws_trust._200512.RequestSecurityTokenResponseCollectionType;
import org.oasis_open.docs.ws_sx.ws_trust._200512.RequestSecurityTokenResponseType;
import org.oasis_open.docs.ws_sx.ws_trust._200512.RequestSecurityTokenType;
import org.oasis_open.docs.ws_sx.ws_trust._200512.RequestedReferenceType;
import org.oasis_open.docs.ws_sx.ws_trust._200512.RequestedSecurityTokenType;
import org.oasis_open.docs.ws_sx.ws_trust._200512.UseKeyType;
import org.oasis_open.docs.wss._2004._01.oasis_200401_wss_wssecurity_secext_1_0.KeyIdentifierType;
import org.oasis_open.docs.wss._2004._01.oasis_200401_wss_wssecurity_secext_1_0.SecurityTokenReferenceType;
import org.opensaml.DefaultBootstrap;
import org.opensaml.common.SAMLVersion;
import org.opensaml.common.impl.SecureRandomIdentifierGenerator;
import org.opensaml.common.xml.SAMLConstants;
import org.opensaml.saml2.core.Assertion;
import org.opensaml.saml2.core.AuthnContext;
import org.opensaml.saml2.core.AuthnContextClassRef;
import org.opensaml.saml2.core.AuthnStatement;
import org.opensaml.saml2.core.Conditions;
import org.opensaml.saml2.core.Issuer;
import org.opensaml.saml2.core.NameID;
import org.opensaml.saml2.core.Subject;
import org.opensaml.saml2.core.SubjectConfirmation;
import org.opensaml.saml2.core.impl.AssertionBuilder;
import org.opensaml.saml2.core.impl.AuthnContextBuilder;
import org.opensaml.saml2.core.impl.AuthnContextClassRefBuilder;
import org.opensaml.saml2.core.impl.AuthnStatementBuilder;
import org.opensaml.saml2.core.impl.ConditionsBuilder;
import org.opensaml.saml2.core.impl.IssuerBuilder;
import org.opensaml.saml2.core.impl.NameIDBuilder;
import org.opensaml.saml2.core.impl.SubjectBuilder;
import org.opensaml.saml2.core.impl.SubjectConfirmationBuilder;
import org.opensaml.xml.Configuration;
import org.opensaml.xml.ConfigurationException;
import org.opensaml.xml.XMLObject;
import org.opensaml.xml.io.Marshaller;
import org.opensaml.xml.io.MarshallingException;
import org.w3._2000._09.xmldsig.KeyInfoType;
import org.w3._2000._09.xmldsig.X509DataType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;


public class IssueDelegate implements IssueOperation {

	private static final Log LOG = LogFactory
			.getLog(SecurityTokenServiceImpl.class.getName());
	private static final org.oasis_open.docs.ws_sx.ws_trust._200512.ObjectFactory WS_TRUST_FACTORY = new org.oasis_open.docs.ws_sx.ws_trust._200512.ObjectFactory();
	private static final org.oasis_open.docs.wss._2004._01.oasis_200401_wss_wssecurity_secext_1_0.ObjectFactory WSSE_FACTORY = new org.oasis_open.docs.wss._2004._01.oasis_200401_wss_wssecurity_secext_1_0.ObjectFactory();
	private static final String SAML_AUTH_CONTEXT = "ac:classes:X509";
	
	private static final String SIGN_FACTORY_TYPE = "DOM";
	private static final String JKS_INSTANCE = "JKS";

	private static final QName QNAME_WST_TOKEN_TYPE = WS_TRUST_FACTORY.createTokenType("").getName();

	private ProviderPasswordCallback passwordCallback;

	private SecureRandomIdentifierGenerator generator;

	private static final String X_509 = "X.509";

	public void setPasswordCallback(ProviderPasswordCallback passwordCallback) {
		this.passwordCallback = passwordCallback;
	}

	@Override
	public RequestSecurityTokenResponseCollectionType issue(
			RequestSecurityTokenType request) {

		String username = passwordCallback.resetUsername();
		String tokenType = null;

		for (Object requestObject : request.getAny()) {
			// certificate
			try {
				X509Certificate certificate = getCertificateFromRequest(requestObject);
				if (certificate != null) {
					username = certificate.getIssuerX500Principal().getName();
				}
			} catch (CertificateException e) {
				throw new STSException("Can't extract X509 certificate from request", e);
			}

			// TokenType
			if(requestObject instanceof JAXBElement) {
				JAXBElement<?> jaxbElement = (JAXBElement<?>)requestObject;
				if(QNAME_WST_TOKEN_TYPE.equals(jaxbElement.getName())) {
					tokenType = (String)jaxbElement.getValue();
				}
			}
		}

		if(username == null) {
			throw new STSException("No credentials provided");
		}

		// should be removed after proper request
		tokenType = SAMLConstants.SAML1_NS;
//		tokenType = SAMLConstants.SAML20_NS;
		boolean saml2 = tokenType.equals(SAMLConstants.SAML20_NS);

		try {
			generator = new SecureRandomIdentifierGenerator();
		} catch (NoSuchAlgorithmException e) {
			throw new STSException("Can't initialize secure random identifier generator", e);
		}

		// Convert SAML to DOM
		Document assertionDocument = null;
		try {
			if(saml2) {
				Assertion samlAssertion = createSAML2Assertion(username);
				assertionDocument = toDom(samlAssertion);
			}
			else {
				org.opensaml.saml1.core.Assertion samlAssertion = createSAML1Assertion(username);
				assertionDocument = toDom(samlAssertion);
			}
		} catch (Exception e) {
			throw new STSException("Can't serialize SAML assertion", e);
		}

		signSAML(assertionDocument);
		
		RequestSecurityTokenResponseType response = wrapAssertionToResponse(
				tokenType,
				assertionDocument.getDocumentElement());

		RequestSecurityTokenResponseCollectionType responseCollection = WS_TRUST_FACTORY
				.createRequestSecurityTokenResponseCollectionType();
		responseCollection.getRequestSecurityTokenResponse().add(response);
		LOG.info("Finished operation requestSecurityToken");
		return responseCollection;
	}

	private static Document toDom(XMLObject object) throws MarshallingException, ParserConfigurationException, ConfigurationException {
		Document document = getDocumentBuilder().newDocument();

		DefaultBootstrap.bootstrap();

		Marshaller out = Configuration.getMarshallerFactory().getMarshaller(
				object);
		out.marshall(object, document);
		return document;
	}

	private static DocumentBuilder getDocumentBuilder() throws ParserConfigurationException {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setValidating(false);
		factory.setNamespaceAware(true);

		return factory.newDocumentBuilder();
	}

	private RequestSecurityTokenResponseType wrapAssertionToResponse(
			String tokenType,
			Element samlAssertion) {
		RequestSecurityTokenResponseType response = WS_TRUST_FACTORY
				.createRequestSecurityTokenResponseType();

		// TokenType
		JAXBElement<String> jaxbTokenType = WS_TRUST_FACTORY.createTokenType(tokenType);
		response.getAny().add(jaxbTokenType);

		// RequestedSecurityToken
		RequestedSecurityTokenType requestedTokenType = WS_TRUST_FACTORY
			.createRequestedSecurityTokenType();
		JAXBElement<RequestedSecurityTokenType> requestedToken = WS_TRUST_FACTORY
				.createRequestedSecurityToken(requestedTokenType);
		requestedTokenType.setAny(samlAssertion);
		response.getAny().add(requestedToken);

		// RequestedAttachedReference
		RequestedReferenceType requestedReferenceType = WS_TRUST_FACTORY
			.createRequestedReferenceType();
		SecurityTokenReferenceType securityTokenReferenceType = WSSE_FACTORY.
			createSecurityTokenReferenceType();
		KeyIdentifierType keyIdentifierType = WSSE_FACTORY
			.createKeyIdentifierType();
		boolean saml2 = tokenType.equals(SAMLConstants.SAML20_NS);
		if(saml2) {
			keyIdentifierType.setValue(samlAssertion.getAttribute(Assertion.ID_ATTRIB_NAME));
		}
		else {
			keyIdentifierType.setValue(samlAssertion.getAttribute(org.opensaml.saml1.core.Assertion.ID_ATTRIB_NAME));
		}
		JAXBElement<KeyIdentifierType> keyIdentifier = WSSE_FACTORY
			.createKeyIdentifier(keyIdentifierType);
		securityTokenReferenceType.getAny().add(keyIdentifier);
		requestedReferenceType.setSecurityTokenReference(securityTokenReferenceType);

		JAXBElement<RequestedReferenceType> requestedAttachedReference = WS_TRUST_FACTORY
			.createRequestedAttachedReference(requestedReferenceType);
		response.getAny().add(requestedAttachedReference);

		return response;
	}

	private Assertion createSAML2Assertion(String nameId) {
		Subject subject = createSubject(nameId);
		return createAuthnAssertion(subject);
	}

	private Subject createSubject(String username) {
		NameID nameID = (new NameIDBuilder()).buildObject();
		nameID.setValue(username);
		String format = "urn:oasis:names:tc:SAML:1.1:nameid-format:transient";
		if (format != null) {
			nameID.setFormat(format);
		}

		Subject subject = (new SubjectBuilder()).buildObject();
		subject.setNameID(nameID);

		String confirmationMethod = "urn:oasis:names:tc:SAML:2.0:cm:bearer";
		if (confirmationMethod != null) {
			SubjectConfirmation confirmation = (new SubjectConfirmationBuilder())
					.buildObject();
			confirmation.setMethod(confirmationMethod);
			subject.getSubjectConfirmations().add(confirmation);
		}
		return subject;
	}

	private Assertion createAuthnAssertion(Subject subject) {
		Assertion assertion = createAssertion(subject);

		AuthnContextClassRef ref = (new AuthnContextClassRefBuilder())
				.buildObject();
		String authnCtx = SAML_AUTH_CONTEXT;
		if (authnCtx != null) {
			ref.setAuthnContextClassRef(authnCtx);
		}
		AuthnContext authnContext = (new AuthnContextBuilder()).buildObject();
		authnContext.setAuthnContextClassRef(ref);

		AuthnStatement authnStatement = (new AuthnStatementBuilder())
				.buildObject();
		authnStatement.setAuthnInstant(new DateTime());
		authnStatement.setAuthnContext(authnContext);

		assertion.getStatements().add(authnStatement);

		return assertion;
	}

	private Assertion createAssertion(Subject subject) {
		Assertion assertion = (new AssertionBuilder()).buildObject();
		assertion.setID(generator.generateIdentifier());

		DateTime now = new DateTime();
		assertion.setIssueInstant(now);

		String issuerURL = "http://www.sopera.de/SAML2";
		if (issuerURL != null) {
			Issuer issuer = (new IssuerBuilder()).buildObject();
			issuer.setValue(issuerURL);
			assertion.setIssuer(issuer);
		}

		assertion.setSubject(subject);

		Conditions conditions = (new ConditionsBuilder()).buildObject();
		conditions.setNotBefore(now.minusMillis(3600000));
		conditions.setNotOnOrAfter(now.plusMillis(3600000));
		assertion.setConditions(conditions);
		return assertion;
	}

	private org.opensaml.saml1.core.Assertion createSAML1Assertion(String nameId) {
		org.opensaml.saml1.core.Subject subject = createSubjectSAML1(nameId);
		return createAuthnAssertionSAML1(subject);
	}

	private org.opensaml.saml1.core.Subject createSubjectSAML1(String username) {
		org.opensaml.saml1.core.NameIdentifier nameID = (new org.opensaml.saml1.core.impl.NameIdentifierBuilder()).buildObject();
		nameID.setNameIdentifier(username);
		String format = "urn:oasis:names:tc:SAML:1.1:nameid-format:transient";

		if (format != null) {
			nameID.setFormat(format);
		}

		org.opensaml.saml1.core.Subject subject = (new org.opensaml.saml1.core.impl.SubjectBuilder()).buildObject();
		subject.setNameIdentifier(nameID);

		String confirmationString = "urn:oasis:names:tc:SAML:1.0:cm:bearer";

		if (confirmationString != null) {

			org.opensaml.saml1.core.ConfirmationMethod confirmationMethod = (new org.opensaml.saml1.core.impl.ConfirmationMethodBuilder()).buildObject();
	        confirmationMethod.setConfirmationMethod(confirmationString);

	        org.opensaml.saml1.core.SubjectConfirmation confirmation = (new org.opensaml.saml1.core.impl.SubjectConfirmationBuilder()).buildObject();
			confirmation.getConfirmationMethods().add(confirmationMethod);

			subject.setSubjectConfirmation(confirmation);
		}
		return subject;
	}

	private org.opensaml.saml1.core.Assertion createAuthnAssertionSAML1(org.opensaml.saml1.core.Subject subject) {
		org.opensaml.saml1.core.AuthenticationStatement authnStatement = (new org.opensaml.saml1.core.impl.AuthenticationStatementBuilder()).buildObject();
        authnStatement.setSubject(subject);
//        authnStatement.setAuthenticationMethod(strAuthMethod);
        
        DateTime now = new DateTime();
        
        authnStatement.setAuthenticationInstant(now);

        org.opensaml.saml1.core.Conditions conditions = (new org.opensaml.saml1.core.impl.ConditionsBuilder()).buildObject();
		conditions.setNotBefore(now.minusMillis(3600000));
		conditions.setNotOnOrAfter(now.plusMillis(3600000));

		String issuerURL = "http://www.sopera.de/SAML1";

		org.opensaml.saml1.core.Assertion assertion = (new org.opensaml.saml1.core.impl.AssertionBuilder()).buildObject();
		assertion.setID(generator.generateIdentifier());
		assertion.setIssuer(issuerURL);
        assertion.setIssueInstant(now);
        assertion.setVersion(SAMLVersion.VERSION_11);

        assertion.getAuthenticationStatements().add(authnStatement);
//        assertion.getAttributeStatements().add(attrStatement);
        assertion.setConditions(conditions);

		return assertion;
	}

	private X509Certificate getCertificateFromRequest(Object requestObject) throws CertificateException {
		UseKeyType useKeyType = extractType(requestObject, UseKeyType.class);
		if(null != useKeyType) {
			KeyInfoType keyInfoType = extractType(useKeyType.getAny(), KeyInfoType.class);
			if(null != keyInfoType) {
				for (Object keyInfoContent : keyInfoType.getContent()) {
					X509DataType x509DataType = extractType(keyInfoContent, X509DataType.class);
					if (null != x509DataType) {
						for (Object x509Object : x509DataType.getX509IssuerSerialOrX509SKIOrX509SubjectName()) {
							byte[] x509 = extractType(x509Object, byte[].class);
							if(null != x509) {
								CertificateFactory cf = CertificateFactory.getInstance(X_509);
								Certificate certificate = cf.generateCertificate(new ByteArrayInputStream(x509));
								X509Certificate ret = (X509Certificate) certificate;
								return ret;
							}
						}
					}
				}
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private static final <T> T extractType(Object param, Class<T> clazz) {
		if(param instanceof JAXBElement) {
			JAXBElement<?> jaxbElement = (JAXBElement<?>)param;
			if (clazz == jaxbElement.getDeclaredType()) {
				return (T)jaxbElement.getValue();
			}
		}
		return null;
	}
	
	private void signSAML(Document assertionDocument) {

		InputStream isKeyStore = this.getClass()
				.getResourceAsStream("/sts.jks");
		String keyAlias = "SecurityTokenServiceProvider";
		String storePwd = "anfang";
		String keyPwd = "anfang";

		KeyStoreInfo keyStoreInfo = new KeyStoreInfo(isKeyStore, storePwd,
				keyAlias, keyPwd);

		signAssertion(assertionDocument.getDocumentElement(), keyStoreInfo);
	}

	private void signAssertion(Element assertion, KeyStoreInfo keyStoreInfo) {

		String id = assertion.getAttribute("ID");
		String refId = (id != null) ? "#" + id : "";

		signXML(assertion, refId, keyStoreInfo);

		shiftSignatureElementInSaml(assertion);
	}

	private void shiftSignatureElementInSaml(Element target) {
		NodeList nl = target.getElementsByTagNameNS(XMLSignature.XMLNS,
				"Signature");
		if (nl.getLength() == 0) {
			return;
		}
		Element signatureElement = (Element) nl.item(0);

		boolean foundIssuer = false;
		Node elementAfterIssuer = null;
		NodeList children = target.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node child = children.item(i);
			if (foundIssuer) {
				elementAfterIssuer = child;
				break;
			}
			if (child.getNodeType() == Node.ELEMENT_NODE
					&& child.getLocalName().equals("Issuer"))
				foundIssuer = true;
		}

		// Place after the Issuer, or as first element if no Issuer:
		if (!foundIssuer || elementAfterIssuer != null) {
			target.removeChild(signatureElement);
			target.insertBefore(signatureElement,
					foundIssuer ? elementAfterIssuer : target.getFirstChild());
		}
	}

	private void signXML(Element target, String refId, KeyStoreInfo keyStoreInfo) {

		org.apache.xml.security.Init.init();

		XMLSignatureFactory signFactory = XMLSignatureFactory
				.getInstance(SIGN_FACTORY_TYPE);
		try {
			DigestMethod method = signFactory.newDigestMethod(
					"http://www.w3.org/2000/09/xmldsig#sha1", null);
			Transform transform = signFactory.newTransform(
					"http://www.w3.org/2000/09/xmldsig#enveloped-signature",
					(TransformParameterSpec) null);
			Reference ref = signFactory.newReference(refId, method,
					Collections.singletonList(transform), null, null);

			CanonicalizationMethod canonMethod = signFactory
					.newCanonicalizationMethod(
							"http://www.w3.org/2001/10/xml-exc-c14n#",
							(C14NMethodParameterSpec) null);
			SignatureMethod signMethod = signFactory.newSignatureMethod(
					"http://www.w3.org/2000/09/xmldsig#rsa-sha1", null);
			SignedInfo si = signFactory.newSignedInfo(canonMethod, signMethod,
					Collections.singletonList(ref));

			KeyStore.PrivateKeyEntry keyEntry = getKeyEntry(keyStoreInfo);
			if (keyEntry == null) {
				throw new IllegalStateException(
						"Key is not found in keystore. Alias: "
								+ keyStoreInfo.getAlias());
			}

			KeyInfo ki = getKeyInfo(signFactory, keyEntry);

			DOMSignContext dsc = new DOMSignContext(keyEntry.getPrivateKey(),
					target);

			XMLSignature signature = signFactory.newXMLSignature(si, ki);

			signature.sign(dsc);

		} catch (Exception e) {
			LOG.error("Cannot sign xml document: " + e.getMessage(), e);
			e.printStackTrace();
		}
	}

	private PrivateKeyEntry getKeyEntry(KeyStoreInfo keyStoreInfo)
			throws KeyStoreException, NoSuchAlgorithmException,
			CertificateException, IOException, UnrecoverableEntryException {

		KeyStore ks = KeyStore.getInstance(JKS_INSTANCE);
		ByteArrayInputStream is = new ByteArrayInputStream(
				keyStoreInfo.getContent());
		ks.load(is, keyStoreInfo.getStorePassword().toCharArray());
		KeyStore.PasswordProtection passwordProtection = new KeyStore.PasswordProtection(
				keyStoreInfo.getKeyPassword().toCharArray());
		KeyStore.PrivateKeyEntry keyEntry = (KeyStore.PrivateKeyEntry) ks
				.getEntry(keyStoreInfo.getAlias(), passwordProtection);
		return keyEntry;
	}

	private KeyInfo getKeyInfo(XMLSignatureFactory signFactory,
			PrivateKeyEntry keyEntry) {

		X509Certificate cert = (X509Certificate) keyEntry.getCertificate();

		KeyInfoFactory kif = signFactory.getKeyInfoFactory();
		List<Object> x509Content = new ArrayList<Object>();
		x509Content.add(cert.getSubjectX500Principal().getName());
		x509Content.add(cert);
		X509Data xd = kif.newX509Data(x509Content);
		return kif.newKeyInfo(Collections.singletonList(xd));
	}

	public class KeyStoreInfo {

		private byte[] content;
		private String storePassword;
		private String alias;
		private String keyPassword;

		public KeyStoreInfo(InputStream is, String storePassword, String alias,
				String keyPassword) {
			this.content = getBytes(is);
			this.alias = alias;
			this.storePassword = storePassword;
			this.keyPassword = keyPassword;
		}

		public byte[] getContent() {
			return content;
		}

		public String getAlias() {
			return alias;
		}

		public String getStorePassword() {
			return storePassword;
		}

		public String getKeyPassword() {
			return keyPassword;
		}

		private byte[] getBytes(InputStream is) {
			try {
				int len;
				int size = 1024;
				byte[] buf;

				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				buf = new byte[size];
				while ((len = is.read(buf, 0, size)) != -1) {
					bos.write(buf, 0, len);
				}
				buf = bos.toByteArray();
				return buf;
			} catch (IOException e) {
				throw new IllegalStateException(
						"Cannot read keystore content: " + e.getMessage(), e);
			}
		}

	}
}