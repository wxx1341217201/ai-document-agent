package com.wxx.aidocumentagent.keyword;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** M08 的 Elasticsearch 索引名称和批量大小全部由部署配置提供。 */
@Validated
@ConfigurationProperties(prefix = "app.keyword")
public class KeywordProperties {

    private boolean enabled = true;

    @NotBlank
    @Pattern(regexp = "[a-z0-9][a-z0-9._-]*", message = "app.keyword.index-name必须是小写Elasticsearch索引名")
    private String indexName = "document_chunks_v1";

    @NotBlank
    @Pattern(regexp = "[a-z0-9][a-z0-9._-]*", message = "app.keyword.alias必须是小写Elasticsearch别名")
    private String alias = "document_chunks";

    @Min(1)
    private int bulkSize = 100;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getIndexName() {
        return indexName;
    }

    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public int getBulkSize() {
        return bulkSize;
    }

    public void setBulkSize(int bulkSize) {
        this.bulkSize = bulkSize;
    }

    @AssertTrue(message = "app.keyword.index-name与alias不能相同")
    public boolean isIndexAndAliasDifferent() {
        return indexName == null || alias == null || !indexName.equals(alias);
    }
}
