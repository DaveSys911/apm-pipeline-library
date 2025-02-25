// Licensed to Elasticsearch B.V. under one or more contributor
// license agreements. See the NOTICE file distributed with
// this work for additional information regarding copyright
// ownership. Elasticsearch B.V. licenses this file to you under
// the Apache License, Version 2.0 (the "License"); you may
// not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

// NOTE: Consumers should use the current tag
// @Library('apm@current') _
// NOTE: Main branch will contain the upcoming release changes
//       this will help us to detect any breaking changes in production.
@Library('apm@main') _

// Global variables can be only set usinig the @Field pattern
import groovy.transform.Field
@Field def variable

pipeline {
  // Top level agent is required to ensure the MBP does populate the environment
  // variables accordingly. Otherwise the built-in environment variables won't
  // be available. It's worthy to use an immutable worker rather than the built-in
  // worker to avoid any kind of bottlenecks or performance issues.
  // NOTE: ephemeral workers cannot be allocated when using `||` see https://github.com/elastic/infra/issues/13823
  agent { label 'linux && immutable' }
  environment {
    // Forced the REPO name to help with some limitations: https://issues.jenkins-ci.org/browse/JENKINS-58450
    REPO = 'apm-pipeline-library'
    // Default BASE_DIR should keep the Golang folder layout as a convention
    // for the rest of the projects/languages independently whether they do need it
    BASE_DIR = "src/github.com/elastic/${env.REPO}"
    // Email are stored as credentials to ensure those emails are not exposed.
    NOTIFY_TO = credentials('notify-to-robots')
    JOB_GCS_BUCKET = credentials('gcs-bucket')
    JOB_GIT_CREDENTIALS = "f6c7695a-671e-4f4f-a331-acdce44ff9ba"
    // The level of verbosity in the messages to be printed during the build.
    // There are so far the below levels: DEBUG, INFO, WARN and ERROR
    PIPELINE_LOG_LEVEL = 'INFO'
    LANG = "C.UTF-8"
    LC_ALL = "C.UTF-8"
    PYTHONUTF8 = "1"
    // Slack channerl where the build notifications will be sent by the notifyBuildResult step.
    SLACK_CHANNEL = '#observablt-bots'
  }
  options {
    // Let's ensure the pipeline doesn't get stale forever.
    timeout(time: 2, unit: 'HOURS')
    // Default build rotation for the pipeline.
    //   When using the downstream pattern for the matrix then the build rotation
    //   should be less restrictive mainly because the @master is the one normally used
    //   in this particular pattern and want to ensure the history of builds is big enough
    //   when debugging. The recommended configuration for that particular setup is:
    //   buildDiscarder(logRotator(numToKeepStr: '100', artifactNumToKeepStr: '100', daysToKeepStr: '30'))
    //
    //   Further details: https://github.com/elastic/apm-agent-python/pull/634
    buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '20'))
    timestamps()
    ansiColor('xterm')
    // Option to disable concurrent builds by aborting previous ones.
    //   Only supported from https://github.com/jenkinsci/workflow-job-plugin/releases/tag/workflow-job-2.42
    disableConcurrentBuilds(abortPrevious: isPR())
    // As long as we use ephemeral workers we cannot use the resume. The below couple of
    // options will help to speed up the performance.
    disableResume()
    durabilityHint('PERFORMANCE_OPTIMIZED')
    // What's the concurrency allowed. For such it's required to configured the JJBB/JJB
    // with the option `concurrent: true`
    rateLimitBuilds(throttle: [count: 60, durationName: 'hour', userBoost: true])
    quietPeriod(10)
  }
  triggers {
    // If required a cron trigger then use the parentstream daily/weekly pipeline helper
    // to trigger it for simplicity. Otherwise, if PRs are not required to run for that
    // particular cron scheduler then it will be required to add the when condition
    // accordingly.
    // cron 'H H(3-4) * * 1-5'
    // obltGitHubComments is the default list of supported GitHub comments for the Observability
    // projects. It can be extended with further regex patterns.
    issueCommentTrigger("(${obltGitHubComments()}|^/run benchmark tests)")
  }
  parameters {
    // Let's use input parameters with capital cases.
    string(name: 'PARAM_WITH_DEFAULT_VALUE', defaultValue: 'defaultValue', description: 'It would not be defined on the first build, see JENKINS-41929.')
    booleanParam(name: 'Run_As_Main_Branch', defaultValue: false, description: 'Allow to run any steps on a PR, some steps normally only run on main branch.')
  }
  stages {
    /**
    Checkout the code and stash it, to use it on other stages.
    */
    stage('Checkout') {
      // NOTE: If not agent is set then it will use the top level one, the suggested
      // approach will be to reduce the number of workers and reuse as much as possible.

      // It's not required to use the default checkout as we do use our specific
      // git checkout implementation, see below.
      options { skipDefaultCheckout() }
      steps {
        // Just in case the workspace is reset.
        deleteDir()

        // Wrapper to trigger certain steps when given certain conditions.
        //   For instance, to cancel all the previous running old builds for the current PR.
        //   This configuration is deprecated in favour of the option disableConcurrentBuilds(abortPrevious: true)
        pipelineManager([ cancelPreviousRunningBuilds: [ when: 'PR' ] ])

        // gitCheckout does expose certain env variables besides of gatekeeping whether
        // the contributor is member from the elastic organisation, it tracks the status
        // with a GitHub check when using a Multibranch Pipeline!
        // Git reference repos are a good practise to speed up the whole execution time.
        gitCheckout(basedir: "${BASE_DIR}",
          repo: "git@github.com:elastic/${env.REPO}.git",
          credentialsId: "${JOB_GIT_CREDENTIALS}",
          githubNotifyFirstTimeContributor: false,
          reference: "/var/lib/jenkins/${env.REPO}.git")

        // This is the way we checkout once using the above step and use the stashed repo
        // in the following stages.
        stash allowEmpty: true, name: 'source', useDefaultExcludes: false

        script {
          // Set the global variable
          variable = 'foo'

          dir("${BASE_DIR}"){
            // Skip all the stages except docs for PR's with asciidoc and md changes only
            env.ONLY_DOCS = isGitRegionMatch(patterns: [ '.*\\.(asciidoc|md)' ], shouldMatchAll: true)

            // Enable Workers Checks stage for PRs with the given pattern
            env.TEST_INFRA = isGitRegionMatch(patterns: [ '(^test-infra|^resources\\/scripts\\/jenkins)\\/.*' ], shouldMatchAll: false)
          }
        }
      }
    }
    stage('Run if GitHub comment on a PR'){
      when {
        beforeAgent true
        expression { return env.GITHUB_COMMENT?.contains('benchmark tests') }
      }
      steps {
        log(level: 'INFO', text: "I'm running as there was a GitHub comment with the 'benchmark tests'")
      }
    }
    stage('Check Unix Workers'){
      when {
        beforeAgent true
        allOf {
          expression { return env.ONLY_DOCS == "false" }
          anyOf {
            expression { return env.TEST_INFRA == "true" }
            branch 'main'
          }
        }
      }
      options { skipDefaultCheckout() }
      environment {
        PATH = "${env.PATH}:${env.WORKSPACE}/bin:${env.WORKSPACE}/${BASE_DIR}/.ci/scripts"
        // Parameters will be empty for the very first build, setting an environment variable
        // with the same name will workaround the issue. see JENKINS-41929
        PARAM_WITH_DEFAULT_VALUE = "${params?.PARAM_WITH_DEFAULT_VALUE}"
      }
      // Use matrix primitive supported by the declarative pipeline.
      // If you need dynamic axis generation or the exclusion list is massive,
      // we do recommend to see the stage('Matrix step') that solves this particular scenarios.
      matrix {
        agent { label "${PLATFORM}" }
        axes {
          axis {
            name 'PLATFORM'
            values (
              'arm',
              'debian-9',
              'ubuntu-18',
              'ubuntu-20'
            )
          }
        }
        stages {
          /**
          Build the project from code..
          */
          stage('Build') {
            steps {
              runBuild()
            }
          }
          /**
          Execute unit tests.
          */
          stage('Test') {
            steps {
              testDockerInside()
              testUnix()
            }
            post {
              always {
                junit(allowEmptyResults: true, keepLongStdio: true, testResults: "${BASE_DIR}/**/junit-*.xml")
              }
            }
          }
          stage('Run on branch or tag'){
            when {
              beforeAgent true
              anyOf {
                branch 'main'
                branch "v7*"
                branch "v8*"
                tag pattern: "v\\d+\\.\\d+\\.\\d+.*", comparator: 'REGEXP'
                expression { return params.Run_As_Main_Branch }
              }
            }
            steps {
              echo "I am a tag or branch"
            }
          }
        }
        post {
          always {
            sh 'docker ps -a || true'
          }
        }
      }
    }
    stage('Check Windows Workers'){
      when {
        beforeAgent true
        allOf {
          expression { return env.ONLY_DOCS == "false" }
          anyOf {
            expression { return env.TEST_INFRA == "true" }
            branch 'main'
          }
        }
      }
      options { skipDefaultCheckout() }
      matrix {
        agent { label "${PLATFORM}" }
        axes {
          axis {
            name 'PLATFORM'
            values (
              'windows-2012-r2-immutable',
              'windows-2016-immutable',
              'windows-2019-immutable',
              'windows-2019-docker-immutable'
              )
          }
        }
        stages {
          stage('Build') {
            steps {
              runBuild()
            }
          }
          stage('Test') {
            steps {
              testWindows()
            }
          }
          stage('Install tools') {
            options {
              warnError('installTools failed')
            }
            steps {
              installTools([ [tool: 'yq', version: '3.3' ] ])
            }
          }
        }
        post {
          always {
            junit(allowEmptyResults: true, keepLongStdio: true, testResults: "${BASE_DIR}/**/junit-*.xml")
          }
        }
      }
    }
    stage('Check Static Workers'){
      when {
        beforeAgent true
        allOf {
          expression { return env.ONLY_DOCS == "false" }
          anyOf {
            expression { return env.TEST_INFRA == "true" }
            branch 'main'
          }
        }
      }
      options { skipDefaultCheckout() }
      failFast false
      parallel {
        stage('BareMetal worker-1799328 check'){
          agent { label 'worker-1799328' }
          steps {
            runBuild()
            testBaremetal()
          }
          post {
            always {
              junit(allowEmptyResults: true, keepLongStdio: true, testResults: "${BASE_DIR}/**/junit-*.xml")
            }
          }
        }
        stage('BareMetal worker-1799329 check'){
          agent { label 'worker-1799329' }
          steps {
            runBuild()
            testBaremetal()
          }
          post {
            always {
              junit(allowEmptyResults: true, keepLongStdio: true, testResults: "${BASE_DIR}/**/junit-*.xml")
            }
          }
        }
        stage('BareMetal worker-1799330 check'){
          agent { label 'worker-1799330' }
          steps {
            runBuild()
            testBaremetal()
          }
          post {
            always {
              junit(allowEmptyResults: true, keepLongStdio: true, testResults: "${BASE_DIR}/**/junit-*.xml")
            }
          }
        }
        stage('BareMetal worker-1213919 check'){
          agent { label 'worker-1213919' }
          steps {
            runBuild()
            testBaremetal()
          }
          post {
            always {
              junit(allowEmptyResults: true, keepLongStdio: true, testResults: "${BASE_DIR}/**/junit-*.xml")
            }
          }
        }
      }
    }
    // Use matrix step within a steps closure if you need dynamic axis generation
    // or the exclusion list is massive.
    stage('Matrix step') {
      steps {
        matrix(
          // agent: 'linux', // If you would like to use a specific agent for each dynamic stage.
          axes:[
            axis('OS', [ 'linux', 'windows', 'darwin' ]),
            axis('PLATFORM', [ '386', 'amd64', 'arm64', 'armv7' ])
          ],
          excludes: [
            axis('OS', [ 'darwin' ]),
            axis('PLATFORM', [ '386', 'armv7' ]),
          ]
        ) {
          echo "${OS} - ${PLATFORM}"
        }
      }
    }
  }
  post {
    cleanup {
      notifyBuildResult(prComment: true, slackComment: true)
    }
  }
}

def testDockerInside(){
  docker.image('node:12').inside(){
    echo "Docker inside"
    dir("${BASE_DIR}"){
      withEnv(["HOME=${env.WORKSPACE}"]){
        sh(script: './resources/scripts/jenkins/build.sh')
      }
    }
  }
}

def runBuild(){
  deleteDir()
  unstash 'source'
  dir("${BASE_DIR}"){
    if (isUnix()) {
      sh(returnStatus: true, script: './resources/scripts/jenkins/build.sh')
    } else {
      bat(returnStatus: true, script: '.\\resources\\scripts\\jenkins\\build.bat')
    }
  }
}

def testUnix(){
  deleteDir()
  unstash 'source'
  dir("${BASE_DIR}"){
    sh returnStatus: true, script: './resources/scripts/jenkins/apm-ci/test.sh'
  }
}

def testBaremetal(){
  deleteDir()
  unstash 'source'
  dir("${BASE_DIR}"){
    sh returnStatus: true, script: './resources/scripts/jenkins/apm-ci/test-baremetal.sh'
  }
}

def testMac(){
  deleteDir()
  unstash 'source'
  dir("${BASE_DIR}"){
    sh returnStatus: true, script: './resources/scripts/jenkins/apm-ci/test-mac.sh'
  }
}

def testWindows(params = [:]){
  def withExtra = params.containsKey('withExtra') ? params.withExtra : hasDocker("${PLATFORM}")
  deleteDir()
  unstash 'source'
  if(isModernWindows("${PLATFORM}")){
    dir("${BASE_DIR}"){
      powershell(script: ".\\resources\\scripts\\jenkins\\apm-ci\\test.ps1 ${withExtra}")
    }
  }
}

def hasDocker(platform){
  return platform.contains('docker')
}

def isModernWindows(platform){
  return platform.contains('2019')
}
