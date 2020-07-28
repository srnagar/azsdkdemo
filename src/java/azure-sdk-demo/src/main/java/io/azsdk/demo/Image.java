package io.azsdk.demo;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Image {
    @JsonProperty("id")
    private String id;
    @JsonProperty("uid")
    private String uid;
    @JsonProperty("title")
    private String title;
    @JsonProperty("url")
    private String url;
    @JsonProperty("extension")
    private String extension;
    @JsonProperty("blobName")
    private String blobName;
    @JsonProperty("blobUri")
    private String blobUri;
    @JsonProperty("text")
    private String text;
    @JsonProperty("sentiment")
    private String sentiment;
    @JsonProperty("status")
    private String status;
    @JsonProperty("createdDate")
    private OffsetDateTime createdDate;
    private ImageType type = ImageType.NEW;

    public String getId() {
        return this.id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUid() {
        return this.uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public String getTitle() {
        return this.title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUrl() {
        return this.url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getExtension() {
        return this.extension;
    }

    public void setExtension(String extension) {
        this.extension = extension;
    }

    public String getBlobName() {
        return this.blobName;
    }

    public void setBlobName(String blobName) {
        this.blobName = blobName;
    }

    public String getBlobUri() {
        return this.blobUri;
    }

    public void setBlobUri(String blobUri) {
        this.blobUri = blobUri;
    }

    public String getText() {
        return this.text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getSentiment() {
        return this.sentiment;
    }

    public void setSentiment(String sentiment) {
        this.sentiment = sentiment;
    }

    public String getStatus() {
        return this.status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public OffsetDateTime getCreatedDate() {
        return this.createdDate;
    }

    public void setCreatedDate(OffsetDateTime createdDate) {
        this.createdDate = createdDate;
    }

    public ImageType getType() {
        return this.type;
    }

    public void setType(ImageType type) {
        this.type = type;
    }
    

}