FROM eclipse-temurin:17-jre
WORKDIR /opt
COPY target/alfresco-community-live-indexing-*.jar app.jar
ENTRYPOINT exec java $JAVA_OPTS -jar app.jar
