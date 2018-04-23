/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.trellisldp.test;

import static javax.ws.rs.client.Entity.entity;
import static javax.ws.rs.core.HttpHeaders.AUTHORIZATION;
import static javax.ws.rs.core.HttpHeaders.LINK;
import static javax.ws.rs.core.Link.fromUri;
import static javax.ws.rs.core.Response.Status.Family.SUCCESSFUL;
import static org.apache.commons.rdf.api.RDFSyntax.TURTLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;
import static org.trellisldp.api.RDFUtils.getInstance;
import static org.trellisldp.http.domain.RdfMediaType.APPLICATION_SPARQL_UPDATE;
import static org.trellisldp.http.domain.RdfMediaType.TEXT_TURTLE;
import static org.trellisldp.test.TestUtils.buildJwt;
import static org.trellisldp.test.TestUtils.getResourceAsString;
import static org.trellisldp.test.TestUtils.readEntityAsGraph;
import static org.trellisldp.vocabulary.RDF.type;

import javax.ws.rs.core.Response;

import org.apache.commons.rdf.api.BlankNodeOrIRI;
import org.apache.commons.rdf.api.Graph;
import org.apache.commons.rdf.api.RDF;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.trellisldp.vocabulary.AS;
import org.trellisldp.vocabulary.LDP;
import org.trellisldp.vocabulary.PROV;
import org.trellisldp.vocabulary.Trellis;

/**
 * Audit tests.
 *
 * @author acoburn
 */
@TestInstance(PER_CLASS)
public interface AuditTests extends CommonTests {

    /**
     * Get the JWT secret.
     * @return the JWT secret
     */
    String getJwtSecret();

    /**
     * Get the location of the test resource.
     * @return the resource URL
     */
    String getResourceLocation();

    /**
     * Set the location of the test resource.
     * @param location the URL
     */
    void setResourceLocation(String location);

    /**
     * Set up the test infrastructure.
     */
    @BeforeAll
    default void beforeAllTests() {
        final String jwt = buildJwt(Trellis.AdministratorAgent.getIRIString(), getJwtSecret());

        final String user1 = buildJwt("https://people.apache.org/~acoburn/#i", getJwtSecret());

        final String user2 = buildJwt("https://madison.example.com/profile#me", getJwtSecret());

        final String container;
        final String containerContent = getResourceAsString("/basicContainer.ttl");

        // POST an LDP-BC
        try (final Response res = target().request()
                .header(LINK, fromUri(LDP.BasicContainer.getIRIString()).rel("type").build())
                .header(AUTHORIZATION, jwt).post(entity(containerContent, TEXT_TURTLE))) {
            assertEquals(SUCCESSFUL, res.getStatusInfo().getFamily());
            container = res.getLocation().toString();
        }

        // POST an LDP-RS
        try (final Response res = target(container).request().header(AUTHORIZATION, jwt)
                .post(entity("", TEXT_TURTLE))) {
            assertEquals(SUCCESSFUL, res.getStatusInfo().getFamily());
            setResourceLocation(res.getLocation().toString());
        }

        // PATCH the LDP-RS
        try (final Response res = target(getResourceLocation()).request().header(AUTHORIZATION, user1)
                .method("PATCH", entity("INSERT { <> <http://purl.org/dc/terms/title> \"A title\" } WHERE {}",
                        APPLICATION_SPARQL_UPDATE))) {
            assertEquals(SUCCESSFUL, res.getStatusInfo().getFamily());
        }

        // PATCH the LDP-RS
        try (final Response res = target(getResourceLocation()).request().header(AUTHORIZATION, user2).method("PATCH",
                    entity("INSERT { <> <http://www.w3.org/2004/02/skos/core#prefLabel> \"Label\" } WHERE {}",
                        APPLICATION_SPARQL_UPDATE))) {
            assertEquals(SUCCESSFUL, res.getStatusInfo().getFamily());
        }
    }

    /**
     * Check the absense of audit triples.
     */
    @Test
    @DisplayName("Check the absense of audit triples.")
    default void testNoAuditTriples() {
        try (final Response res = target(getResourceLocation()).request().get()) {
            final Graph g = readEntityAsGraph(res.getEntity(), getBaseURL(), TURTLE);
            assertEquals(2L, g.size());
        }
    }

    /**
     * Check the explicit absense of audit triples.
     */
    @Test
    @DisplayName("Check the explicit absense of audit triples.")
    default void testOmitAuditTriples() {
        try (final Response res = target(getResourceLocation()).request().header("Prefer",
                    "return=representation; omit=\"" + Trellis.PreferAudit.getIRIString() + "\"").get()) {
            final Graph g = readEntityAsGraph(res.getEntity(), getBaseURL(), TURTLE);
            assertEquals(2L, g.size());
        }
    }

    /**
     * Check the presence of audit triples.
     */
    @Test
    @DisplayName("Check the presence of audit triples.")
    default void testAuditTriples() {
        final RDF rdf = getInstance();
        try (final Response res = target(getResourceLocation()).request().header("Prefer",
                    "return=representation; include=\"" + Trellis.PreferAudit.getIRIString() + "\"").get()) {
            final Graph g = readEntityAsGraph(res.getEntity(), getBaseURL(), TURTLE);
            assertEquals(3L, g.stream(rdf.createIRI(getResourceLocation()), PROV.wasGeneratedBy, null).count());
            g.stream(rdf.createIRI(getResourceLocation()), PROV.wasGeneratedBy, null).forEach(triple -> {
                assertTrue(g.contains((BlankNodeOrIRI) triple.getObject(), type, PROV.Activity));
                assertTrue(g.contains((BlankNodeOrIRI) triple.getObject(), PROV.atTime, null));
                assertEquals(4L, g.stream((BlankNodeOrIRI) triple.getObject(), null, null).count());
            });
            assertTrue(g.contains(null, PROV.wasAssociatedWith, Trellis.AdministratorAgent));
            assertTrue(g.contains(null, PROV.wasAssociatedWith,
                        rdf.createIRI("https://madison.example.com/profile#me")));
            assertTrue(g.contains(null, PROV.wasAssociatedWith,
                        rdf.createIRI("https://people.apache.org/~acoburn/#i")));
            assertEquals(2L, g.stream(null, type, AS.Update).count());
            assertEquals(1L, g.stream(null, type, AS.Create).count());
            assertEquals(17L, g.size());
        }
    }
}
