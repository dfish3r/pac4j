package org.pac4j.saml.metadata.jdbc;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.opensaml.saml.metadata.resolver.impl.AbstractMetadataResolver;
import org.opensaml.saml.metadata.resolver.impl.DOMMetadataResolver;
import org.opensaml.saml.saml2.metadata.EntityDescriptor;
import org.pac4j.core.util.CommonHelper;
import org.pac4j.saml.exceptions.SAMLException;
import org.pac4j.saml.metadata.BaseSAML2MetadataGenerator;
import org.pac4j.saml.util.Configuration;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * This is {@link SAML2JdbcMetadataGenerator}
 * that stores service provider metadata in a relational database.
 *
 * @author Misagh Moayyed
 * @since 5.7.0
 */
@RequiredArgsConstructor
@Getter
@Setter
public class SAML2JdbcMetadataGenerator extends BaseSAML2MetadataGenerator {
    private String tableName = "sp_metadata";

    private final JdbcTemplate template;

    private final String entityId;

    /** {@inheritDoc} */
    @Override
    @SuppressWarnings("PMD.NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    public AbstractMetadataResolver createMetadataResolver() throws Exception {
        var metadata = fetchMetadata();
        try (var is = new ByteArrayInputStream(metadata)) {
            var document = Configuration.getParserPool().parse(is);
            var root = document.getDocumentElement();
            return new DOMMetadataResolver(root);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean storeMetadata(final String metadata, final boolean force) {
        if (StringUtils.isBlank(metadata)) {
            logger.info("No metadata is provided");
            return false;
        }

        var metadataToUse = isSignMetadata() ? getMetadataSigner().sign(metadata) : metadata;
        CommonHelper.assertNotBlank("metadata", metadataToUse);

        var metadataEntityId = Configuration.deserializeSamlObject(metadataToUse)
            .map(EntityDescriptor.class::cast)
            .map(EntityDescriptor::getEntityID)
            .orElseThrow();
        if (!metadataEntityId.equals(this.entityId)) {
            throw new SAMLException("Entity id from metadata " + metadataEntityId
                + " does not match supplied entity id " + this.entityId);
        }

        try {
            var sql = String.format("SELECT entityId FROM %s WHERE entityId='%s'", this.tableName, this.entityId);
            var entityId = template.queryForObject(sql, String.class);
            logger.debug("Updating metadata entity [{}]", entityId);
            return updateMetadata(metadataToUse);
        } catch (final EmptyResultDataAccessException e) {
            return insertMetadata(metadataToUse);
        }
    }

    /**
     * <p>updateMetadata.</p>
     *
     * @param metadataToUse a {@link String} object
     * @return a boolean
     */
    protected boolean updateMetadata(final String metadataToUse) {
        var updateSql = String.format("UPDATE %s SET metadata='%s' WHERE entityId='%s'", this.tableName,
            encodeMetadata(metadataToUse), this.entityId);
        var count = template.update(updateSql);
        return count > 0;
    }

    /**
     * <p>insertMetadata.</p>
     *
     * @param metadataToUse a {@link String} object
     * @return a boolean
     */
    protected boolean insertMetadata(String metadataToUse) {
        var insert = new SimpleJdbcInsert(this.template)
            .withTableName(String.format("%s", this.tableName))
            .usingColumns("entityId", "metadata");
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("entityId", this.entityId);
        parameters.put("metadata", encodeMetadata(metadataToUse));
        return insert.execute(parameters) > 0;
    }

    /**
     * <p>fetchMetadata.</p>
     *
     * @return an array of {@link byte} objects
     */
    protected byte[] fetchMetadata() {
        var sql = String.format("SELECT metadata FROM %s WHERE entityId='%s'", this.tableName, this.entityId);
        return decodeMetadata(template.queryForObject(sql, String.class));
    }

    /**
     * <p>decodeMetadata.</p>
     *
     * @param metadata a {@link String} object
     * @return an array of {@link byte} objects
     */
    protected byte[] decodeMetadata(final String metadata) {
        return Base64.getDecoder().decode(metadata);
    }

    /**
     * <p>encodeMetadata.</p>
     *
     * @param metadataToUse a {@link String} object
     * @return a {@link String} object
     */
    protected String encodeMetadata(String metadataToUse) {
        return Base64.getEncoder().encodeToString(metadataToUse.getBytes(StandardCharsets.UTF_8));
    }
}
