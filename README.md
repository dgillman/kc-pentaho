This is an example of a Servlet for generating reports based on report definitions from
the Pentaho Report Designer (.prpt files). At present this is hard-coded to work with
a single query against the Kuali Coeus database. It could be extended to be more dynamic
with very little effort.

SETUP
=====

1. Build and deploy Kuali Coeus using a MySQL database.

2. Create several Award documents within Coeus

3. Edit the file src/main/webapp/WEB-INF/datasource.properties in this project to include
   credentials and connection information for the Kuali Coeus database.
   
4. Build and deploy this project.

5. Visit the URL:

    http://localhost:8080/kc-reporting?report=awards.prpt&type=HTML
    
   You can replace HTML with PDF or XLS for other formats