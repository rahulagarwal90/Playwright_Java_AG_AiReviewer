pipeline {
    agent any

    tools {
        maven 'Maven 3.8' // Configure in Jenkins Global Tool Configuration
        jdk 'JDK 17'      // Configure in Jenkins Global Tool Configuration
    }

    parameters {
        choice(name: 'BROWSER', choices: ['chrome', 'firefox', 'webkit'], description: 'Select the browser for test execution')
        string(name: 'TAGS', defaultValue: '@SauceDemo', description: 'Enter the Cucumber tags to execute (e.g., @SauceDemo)')
        string(name: 'WORKERS', defaultValue: '2', description: 'Number of parallel workers')
    }

    triggers {
        cron('H 02 * * *')     // Nightly run at 2 AM
        pollSCM('H/5 * * * *') // Localhost Auto-trigger: Check for changes every 5 mins
    }

    environment {
        // Map parameters to environment variables
        BROWSER = "${params.BROWSER}"
        TAGS = "${params.TAGS}"
        WORKERS = "${params.WORKERS}"
    }

    stages {
        stage('Install Playwright Browsers') {
            steps {
                script {
                    if (isUnix()) {
                        sh 'mvn exec:java -e -D exec.mainClass="com.microsoft.playwright.CLI" -D exec.args="install --with-deps"'
                    } else {
                        bat 'mvn exec:java -e -D exec.mainClass="com.microsoft.playwright.CLI" -D exec.args="install --with-deps"'
                    }
                }
            }
        }
        
        stage('Test Execution') {
            steps {
                script {
                    if (isUnix()) {
                        sh "mvn clean test -Dbrowser.headless=true -Dbrowser=${env.BROWSER} -Dcucumber.filter.tags=${env.TAGS} -Dworkers=${env.WORKERS}"
                    } else {
                        bat "mvn clean test -Dbrowser.headless=true -Dbrowser=${env.BROWSER} -Dcucumber.filter.tags=${env.TAGS} -Dworkers=${env.WORKERS}"
                    }
                }
            }
        }
    }

    post {
        always {
            // 1. Publish Allure Report (Requires Allure Jenkins Plugin)
            allure includeProperties: false, results: [[path: 'target/allure-results']]
            
            // 2. Publish Standard JUnit Reports
            junit 'target/surefire-reports/*.xml'
            
            // 3. Archive Test Artifacts (Videos, Screenshots, Traces)
            archiveArtifacts artifacts: 'target/videos/**/*, target/screenshots/**/*, target/traces/**/*', allowEmptyArchive: true
        }
    }
}
