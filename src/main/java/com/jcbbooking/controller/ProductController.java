package com.jcbbooking.controller;

import com.jcbbooking.model.Product;
import com.jcbbooking.repository.ProductRepository;
import com.jcbbooking.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
@Slf4j
public class ProductController {

    private final ProductRepository productRepository;
    private final com.jcbbooking.repository.PricingRepository pricingRepository;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Product>>> getAllProducts() {
        log.info("REST request to get all products");
        return ResponseEntity.ok(ApiResponse.success("Products retrieved successfully", productRepository.findAll()));
    }

    @GetMapping("/machinery-catalog")
    public ResponseEntity<ApiResponse<List<java.util.Map<String, Object>>>> getMachineryCatalog() {
        log.info("REST request to get dynamic machinery catalog");
        List<Product> machineryProducts = productRepository.findAll().stream()
                .filter(p -> Boolean.TRUE.equals(p.getActive()))
                .filter(p -> "HEAVY_EQUIPMENT".equalsIgnoreCase(p.getProductType())
                        || "JCB".equalsIgnoreCase(p.getProductType())
                        || "MACHINERY".equalsIgnoreCase(p.getProductType())
                        || "EQUIPMENT".equalsIgnoreCase(p.getProductType()))
                .toList();

        List<java.util.Map<String, Object>> result = new java.util.ArrayList<>();
        for (Product p : machineryProducts) {
            com.jcbbooking.model.Pricing pricing = pricingRepository.findByProductId(p.getId()).orElse(null);
            java.util.Map<String, Object> item = new java.util.HashMap<>();
            item.put("id", p.getCode() != null ? p.getCode().toLowerCase() : String.valueOf(p.getId()));
            item.put("productId", p.getId());
            item.put("name", p.getName());
            item.put("code", p.getCode());
            item.put("category", p.getCategory() != null ? p.getCategory() : "Construction");
            item.put("type", p.getCategory() != null ? p.getCategory() : "Heavy Equipment");
            item.put("hourlyRate", (pricing != null && pricing.getPerHourPrice() != null && pricing.getPerHourPrice() > 0)
                    ? pricing.getPerHourPrice()
                    : (pricing != null && pricing.getBasePrice() != null ? pricing.getBasePrice() : 1000.0));
            item.put("dailyRate", (pricing != null && pricing.getDailyRate() != null && pricing.getDailyRate() > 0)
                    ? pricing.getDailyRate()
                    : 8000.0);
            item.put("advancePercentage", 30);
            item.put("image", p.getImageUrl() != null && !p.getImageUrl().isEmpty() ? p.getImageUrl() : "🚜");
            item.put("specs", p.getDescription());
            item.put("description", p.getDescription() != null ? p.getDescription() : p.getName());
            result.add(item);
        }
        return ResponseEntity.ok(ApiResponse.success("Machinery catalog retrieved successfully", result));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Product>> getProductById(@PathVariable Long id) {
        log.info("REST request to get product by id: {}", id);
        return productRepository.findById(id)
                .map(product -> ResponseEntity.ok(ApiResponse.success("Product retrieved successfully", product)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @Transactional
    public ResponseEntity<ApiResponse<Product>> saveProduct(@RequestBody Product product) {
        log.info("REST request to save product: {}", product);
        boolean isNew = (product.getId() == null || product.getId() == 0);

        if (isNew) {
            product.setId(null);
            if (productRepository.existsByCode(product.getCode())) {
                return ResponseEntity.badRequest().body(ApiResponse.error("Product code already exists: " + product.getCode()));
            }
        } else {
            Product existing = productRepository.findById(product.getId()).orElse(null);
            if (existing == null) {
                return ResponseEntity.notFound().build();
            }
            existing.setName(product.getName());
            existing.setCode(product.getCode());
            existing.setProductType(product.getProductType());
            existing.setCategory(product.getCategory());
            existing.setDescription(product.getDescription());
            existing.setImageUrl(product.getImageUrl());
            if (product.getActive() != null) {
                existing.setActive(product.getActive());
            }
            product = existing;
        }

        Product saved = productRepository.save(product);
        return ResponseEntity.ok(ApiResponse.success(isNew ? "Product created successfully" : "Product updated successfully", saved));
    }

    @org.springframework.beans.factory.annotation.Value("${core.fileTransfer.primaryUploadFolder:/opt/microservice/upload/images}")
    private String primaryUploadFolder;

    private String resolveBaseDirectory() {
        String baseDirStr = primaryUploadFolder;
        try {
            java.io.File baseDir = new java.io.File(baseDirStr);
            if (!baseDir.exists()) {
                baseDir.mkdirs();
            }
            if (!baseDir.canWrite()) {
                baseDirStr = System.getProperty("user.dir") + java.io.File.separator + "uploads" + java.io.File.separator + "images";
                new java.io.File(baseDirStr).mkdirs();
            }
        } catch (Exception ex) {
            log.warn("Lacking permissions for configured path: {}. Falling back to workspace folders.", baseDirStr);
            baseDirStr = System.getProperty("user.dir") + java.io.File.separator + "uploads" + java.io.File.separator + "images";
            new java.io.File(baseDirStr).mkdirs();
        }
        return baseDirStr;
    }

    @PostMapping("/{id}/upload-images")
    public ResponseEntity<ApiResponse<Product>> uploadProductImages(
            @PathVariable Long id,
            @RequestParam("files") org.springframework.web.multipart.MultipartFile[] files) {
        log.info("REST request to upload {} files for product ID: {}", files != null ? files.length : 0, id);
        Product product = productRepository.findById(id).orElse(null);
        if (product == null) {
            return ResponseEntity.notFound().build();
        }

        try {
            String baseDirStr = resolveBaseDirectory();
            String targetFolderStr = baseDirStr + java.io.File.separator + "product" + java.io.File.separator + id;
            java.io.File targetFolder = new java.io.File(targetFolderStr);
            if (!targetFolder.exists()) {
                targetFolder.mkdirs();
            }

            java.util.List<String> uploadedUrls = new java.util.ArrayList<>();
            if (files != null) {
                for (org.springframework.web.multipart.MultipartFile file : files) {
                    if (file.isEmpty()) continue;
                    String originalFilename = file.getOriginalFilename();
                    String cleanName = System.currentTimeMillis() + "_" + (originalFilename != null ? originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_") : "image.jpg");
                    
                    java.nio.file.Path destinationPath = java.nio.file.Paths.get(targetFolderStr, cleanName);
                    java.nio.file.Files.copy(file.getInputStream(), destinationPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                    uploadedUrls.add("/uploads/product/" + id + "/" + cleanName);
                }
            }

            java.util.List<String> currentList = new java.util.ArrayList<>();
            String existingStr = product.getImageUrl();
            if (existingStr != null && !existingStr.trim().isEmpty()) {
                String clean = existingStr.replaceAll("[\\[\\]\"]", "");
                for (String part : clean.split(",")) {
                    if (!part.trim().isEmpty()) {
                        currentList.add(part.trim());
                    }
                }
            }
            currentList.addAll(uploadedUrls);

            product.setImageUrl(String.join(",", currentList));
            Product saved = productRepository.save(product);
            return ResponseEntity.ok(ApiResponse.success("Images saved to C:\\opt\\microservice\\upload\\images\\product\\" + id, saved));
        } catch (Exception e) {
            log.error("Error saving product images", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Image upload failed: " + e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> deleteProduct(@PathVariable Long id) {
        log.info("REST request to delete product ID: {}", id);
        if (!productRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        productRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success("Product deleted successfully"));
    }
}
