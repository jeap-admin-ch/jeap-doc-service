package ch.admin.bit.jeap.doc.web.api.upload;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Limits the doc service applies to an upload.
 */
@Data
@ConfigurationProperties("jeap.doc.upload")
class UploadProperties {

    /**
     * Maximum size of an uploaded bundle. A larger bundle is rejected without being read.
     */
    private DataSize maxSize = DataSize.ofMegabytes(100);
}
