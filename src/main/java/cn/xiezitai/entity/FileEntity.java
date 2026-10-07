package cn.xiezitai.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "files")
public class FileEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 300)
    private String originalName;

    /** 存储文件名（重命名，防路径穿越） */
    @Column(unique = true, nullable = false, length = 120)
    private String storedName;

    @Column(length = 100)
    private String contentType;

    private Long size;

    @Column(length = 50)
    private String uploader;

    /** 内容 SHA-256（64 hex）：同字节流只落一份盘，重复写入引用同一实体；老数据为 null（不参与去重） */
    @Column(name = "sha256", length = 64)
    private String sha256;

    /** PENDING / SAFE / DANGEROUS */
    private String scanStatus = "PENDING";

    @Column(length = 500)
    private String scanDetail;

    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
    public String getStoredName() { return storedName; }
    public void setStoredName(String storedName) { this.storedName = storedName; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getSize() { return size; }
    public void setSize(Long size) { this.size = size; }
    public String getUploader() { return uploader; }
    public void setUploader(String uploader) { this.uploader = uploader; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public String getScanStatus() { return scanStatus; }
    public void setScanStatus(String scanStatus) { this.scanStatus = scanStatus; }
    public String getScanDetail() { return scanDetail; }
    public void setScanDetail(String scanDetail) { this.scanDetail = scanDetail; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
