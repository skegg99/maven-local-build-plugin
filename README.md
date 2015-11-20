# maven-local-build-plugin
Collects non-public maven dependencies to local file repository in order to distribute it with sources

This will allow to distribute projects depending on non-public artifacts from private repository. It will create a local file repository to be distributed with source code. Not the best practice, but we need it sometimes.

Plugin is really slow and makes a lot of assumptions. Multimodule projects parent module, for example, must have a name "project" (artifactId) or "project.parent". All projects must be deployed to repository and local repo must me emptied (remove repository folder from ~/.m2).

## Configuration

Add a profile to parent pom.xml

	      <profile>
            <id>collect</id>
            <activation>
                <activeByDefault>false</activeByDefault>
            </activation>
            <build>
                <plugins>
                    <plugin>
                        <groupId>com.guanoislands</groupId>
                        <artifactId>maven.localbuild-plugin</artifactId>
                        <version>1.0-SNAPSHOT</version>
                        <configuration>
                            <targetRepositoryPath>${main.basedir}/shipment/build</targetRepositoryPath>
                            <includes>
                                <include>
                                    <groupIdMask>*</groupIdMask>
                                    <artifactIdMask>*</artifactIdMask>
                                </include>
                            </includes>
                        </configuration>
                        <executions>
                            <execution>
                                <phase>compile</phase>
                                <goals>
                                    <goal>collect</goal>
                                </goals>
                            </execution>
                        </executions>
                    </plugin>
                </plugins>
            </build>
        </profile>

And a repository pointing to local folder pointing at targetRepositoryPath from plugin configuration above

        <repository>
            <id>local-build-repo</id>
            <name>local-build-repo</name>
            <url>file://${main.basedir}/shipment/build</url>
        </repository>

## Usage 

mvn clean install -P collect 

will collect private dependencies to local file repository, and it will be possible to build project in absence of private repository. 

Includes and excludes will allow to include or exclude certain artifacts by mask:

  <include>
    <groupIdMask>com.ibm*</groupIdMask>
    <artifactIdMask>*</artifactIdMask>
  </include>
