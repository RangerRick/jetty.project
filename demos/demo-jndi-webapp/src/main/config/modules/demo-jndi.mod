# DO NOT EDIT THIS FILE - See: https://eclipse.dev/jetty/documentation/

[description]
Demo JNDI Resources Webapp

[tags]
demo
webapp

[depends]
deploy
ext
jdbc
plus
demo-mock-resources

[files]
basehome:modules/demo.d/demo-jndi.xml|webapps/demo-jndi.xml
maven://org.eclipse.jetty.demos/demo-jndi-webapp/${jetty.version}/war|webapps/demo-jndi.war
maven://org.eclipse.jetty.orbit/javax.mail.glassfish/@javax.mail.glassfish.version@/jar|lib/ext/javax.mail.glassfish-@javax.mail.glassfish.version@.jar
maven://jakarta.transaction/jakarta.transaction-api/@jakarta.transaction-api.version@/jar|lib/ext/jakarta.transaction-api-@jakarta.transaction-api.version@.jar
