# The runtime includes local Chinese/English OCR for PDFs with unusable text layers.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY src/main ./src/main
RUN --mount=type=cache,target=/root/.m2 mvn -B -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl fontconfig font-noto-cjk tesseract-ocr tesseract-ocr-data-chi_sim tesseract-ocr-data-eng \
    && addgroup -S app && adduser -S -G app app \
    && mkdir -p /app/data/documents && chown -R app:app /app
WORKDIR /app
ENV DOCUMENT_OCR_ENABLED=true
COPY --from=build --chown=app:app /workspace/target/*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
