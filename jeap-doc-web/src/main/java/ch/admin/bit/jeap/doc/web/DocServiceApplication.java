package ch.admin.bit.jeap.doc.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The jEAP doc service: it receives the documentation of systems, components and libraries, stores it on S3,
 * generates the documentation site and serves it.
 */
@SpringBootApplication
public class DocServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocServiceApplication.class, args);
    }
}
