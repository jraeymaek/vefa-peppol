/*
 * Copyright 2016 Direktoratet for forvaltning og IKT
 *
 * Licensed under the EUPL, Version 1.1 or – as soon they
 * will be approved by the European Commission - subsequent
 * versions of the EUPL (the "Licence");
 *
 * You may not use this work except in compliance with the Licence.
 *
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/community/eupl/og_page/eupl
 *
 * Unless required by applicable law or agreed to in
 * writing, software distributed under the Licence is
 * distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied.
 * See the Licence for the specific language governing
 * permissions and limitations under the Licence.
 */

package no.difi.vefa.peppol.lookup.reader;

import no.difi.vefa.peppol.common.api.Perform;
import no.difi.vefa.peppol.common.lang.PeppolRuntimeException;
import no.difi.vefa.peppol.common.model.*;
import no.difi.vefa.peppol.lookup.api.FetcherResponse;
import no.difi.vefa.peppol.lookup.api.LookupException;
import no.difi.vefa.peppol.lookup.api.MetadataReader;
import no.difi.vefa.peppol.lookup.model.DocumentTypeIdentifierWithUri;
import no.difi.vefa.peppol.security.lang.PeppolSecurityException;
import no.difi.vefa.peppol.security.xmldsig.DomUtils;
import no.difi.vefa.peppol.security.xmldsig.XmldsigVerifier;
import org.oasis_open.docs.bdxr.ns.smp._2016._05.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

public class BdxrReader implements MetadataReader {

    private static Logger logger = LoggerFactory.getLogger(BdxrReader.class);

    public static final String NAMESPACE_201407 = "http://docs.oasis-open.org/bdxr/ns/SMP/2014/07";
    public static final String NAMESPACE_201605 = "http://docs.oasis-open.org/bdxr/ns/SMP/2016/05";

    private static JAXBContext jaxbContext;

    private static CertificateFactory certificateFactory;

    static {
        PeppolRuntimeException.verify(new Perform() {
            @Override
            public void action() throws Exception {
                jaxbContext = JAXBContext.newInstance(ServiceGroupType.class, SignedServiceMetadataType.class,
                        ServiceMetadataType.class);
                certificateFactory = CertificateFactory.getInstance("X.509");
            }
        });
    }

    @Override
    public List<DocumentTypeIdentifier> parseDocumentIdentifiers(FetcherResponse fetcherResponse)
            throws LookupException {
        try {
            Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
            ServiceGroupType serviceGroup = unmarshaller.unmarshal(
                    new StreamSource(fetcherResponse.getInputStream()), ServiceGroupType.class).getValue();
            List<DocumentTypeIdentifier> documentTypeIdentifiers = new ArrayList<>();

            for (ServiceMetadataReferenceType reference :
                    serviceGroup.getServiceMetadataReferenceCollection().getServiceMetadataReference()) {
                String hrefDocumentTypeIdentifier =
                        URLDecoder.decode(reference.getHref().split("/services/")[1], "UTF-8");
                String[] parts = hrefDocumentTypeIdentifier.split("::", 2);

                try {
                    documentTypeIdentifiers.add(DocumentTypeIdentifierWithUri.of(
                            parts[1], Scheme.of(parts[0]), URI.create(reference.getHref())));
                } catch (ArrayIndexOutOfBoundsException e) {
                    logger.warn("Unable to parse '{}'.", hrefDocumentTypeIdentifier);
                }
            }

            return documentTypeIdentifiers;
        } catch (JAXBException | UnsupportedEncodingException e) {
            throw new LookupException(e.getMessage(), e);
        }
    }

    @Override
    public ServiceMetadata parseServiceMetadata(FetcherResponse fetcherResponse)
            throws LookupException, PeppolSecurityException {
        try {
            Document doc = DomUtils.parse(fetcherResponse.getInputStream());

            Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();

            Object o;
            if (doc.getDocumentElement().getTagName().equals("SignedServiceMetadata")) {
                o = unmarshaller.unmarshal(new DOMSource(doc), SignedServiceMetadataType.class).getValue();
            } else {
                o = unmarshaller.unmarshal(new DOMSource(doc), ServiceMetadataType.class).getValue();
            }

            X509Certificate signer = null;
            if (o instanceof SignedServiceMetadataType) {
                signer = XmldsigVerifier.verify(doc);
                o = ((SignedServiceMetadataType) o).getServiceMetadata();
            }

            ServiceInformationType serviceInformation = ((ServiceMetadataType) o).getServiceInformation();

            List<Endpoint> endpoints = new ArrayList<>();
            for (ProcessType processType : serviceInformation.getProcessList().getProcess()) {
                for (EndpointType endpointType : processType.getServiceEndpointList().getEndpoint()) {
                    endpoints.add(Endpoint.of(
                            ProcessIdentifier.of(
                                    processType.getProcessIdentifier().getValue(),
                                    Scheme.of(processType.getProcessIdentifier().getScheme())
                            ),
                            TransportProfile.of(endpointType.getTransportProfile()),
                            endpointType.getEndpointURI(),
                            certificateInstance(endpointType.getCertificate())
                    ));
                }
            }

            return ServiceMetadata.of(
                    ParticipantIdentifier.of(
                            serviceInformation.getParticipantIdentifier().getValue(),
                            Scheme.of(serviceInformation.getParticipantIdentifier().getScheme())
                    ),
                    DocumentTypeIdentifier.of(
                            serviceInformation.getDocumentIdentifier().getValue(),
                            Scheme.of(serviceInformation.getDocumentIdentifier().getScheme())
                    ),
                    endpoints,
                    signer
            );
        } catch (JAXBException | CertificateException | IOException | SAXException | ParserConfigurationException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private X509Certificate certificateInstance(byte[] content) throws CertificateException {
        return (X509Certificate) certificateFactory.generateCertificate(new ByteArrayInputStream(content));
    }
}
