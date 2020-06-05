FROM openjdk:11-jre

ADD target/obscure-1.0-standalone.jar /obscure.jar

CMD java -XX:-OmitStackTraceInFastThrow -cp /obscure.jar obscure.core
