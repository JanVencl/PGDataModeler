package com.datagocar.web;

import com.datagocar.ddl.LiquibaseSqlGenerator;
import com.datagocar.ddl.LiquibaseXmlGenerator;
import com.datagocar.ddl.ModelValidator;
import com.datagocar.ddl.PostgresDdlGenerator;
import com.datagocar.model.DataModel;
import com.datagocar.service.ModelService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/models/{id}")
public class ExportController {

    public enum Format { SQL, LIQUIBASE_XML, LIQUIBASE_SQL, JSON }

    private final ModelService models;
    private final ObjectMapper objectMapper;
    private final String liquibaseAuthor;

    public ExportController(ModelService models, ObjectMapper objectMapper,
                            @Value("${datagocar.export.liquibase-author:datagocar}") String liquibaseAuthor) {
        this.models = models;
        this.objectMapper = objectMapper;
        this.liquibaseAuthor = liquibaseAuthor;
    }

    /**
     * {@code format}: sql | liquibase-xml | liquibase-sql | json. With {@code download=true} the response is
     * sent as an attachment.
     */
    @GetMapping("/export")
    public ResponseEntity<String> export(@PathVariable UUID id,
                                         @RequestParam(defaultValue = "sql") String format,
                                         @RequestParam(defaultValue = "false") boolean download,
                                         @RequestParam(required = false) String author) throws JsonProcessingException {
        DataModel model = models.document(id);
        Format f = Format.valueOf(format.trim().toUpperCase().replace('-', '_'));
        String changesetAuthor = author == null || author.isBlank() ? liquibaseAuthor : author.trim();
        String body = switch (f) {
            case SQL -> new PostgresDdlGenerator().generate(model);
            case LIQUIBASE_XML -> new LiquibaseXmlGenerator().generate(model, changesetAuthor);
            case LIQUIBASE_SQL -> new LiquibaseSqlGenerator().generate(model, changesetAuthor);
            case JSON -> objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(model);
        };
        String extension = switch (f) {
            case SQL -> ".sql";
            case LIQUIBASE_XML -> ".changelog.xml";
            case LIQUIBASE_SQL -> ".changelog.sql";
            case JSON -> ".datagocar.json";
        };
        MediaType type = switch (f) {
            case LIQUIBASE_XML -> MediaType.APPLICATION_XML;
            case JSON -> MediaType.APPLICATION_JSON;
            default -> MediaType.TEXT_PLAIN;
        };
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType(type, StandardCharsets.UTF_8));
        if (download) {
            String file = model.name().replaceAll("[^A-Za-z0-9._-]+", "_") + extension;
            headers.setContentDisposition(ContentDisposition.attachment().filename(file).build());
        }
        return ResponseEntity.ok().headers(headers).body(body);
    }

    @GetMapping("/validation")
    public List<ModelValidator.Issue> validate(@PathVariable UUID id) {
        return new ModelValidator().validate(models.document(id));
    }
}
