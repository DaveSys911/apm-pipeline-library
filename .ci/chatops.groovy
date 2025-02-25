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

@Library('apm@current') _

pipeline {
  agent none
  environment {
    REPO = 'apm-pipeline-library'
    BASE_DIR = "src/github.com/elastic/${env.REPO}"
    JOB_GIT_CREDENTIALS = "f6c7695a-671e-4f4f-a331-acdce44ff9ba"
    PIPELINE_LOG_LEVEL = 'INFO'
    BRANCH_NAME = "${params.branch_specifier}"
  }
  options {
    timeout(time: 1, unit: 'HOURS')
    buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '20'))
    timestamps()
    ansiColor('xterm')
    disableResume()
    durabilityHint('PERFORMANCE_OPTIMIZED')
  }
  triggers {
    // most of then come from https://prow.k8s.io/command-help
    issueCommentTrigger('(?i)^\\/(run|test|lgtm|cc|assign|approve|meow|woof|bark|this-is-|lint|help|hold|label|close|reopen|skip|ok-to-test|package|build|deploy)(-\\w+)?(\\s+.*)?$')
  }
  parameters {
    string(name: 'branch_specifier', defaultValue: "main", description: "the Git branch specifier to build")
  }
  stages {
    /**
     Checkout the code and stash it, to use it on other stages.
    */
    stage('Checkout') {
      agent any
      when {
        beforeAgent true
        expression {
          return isCommentTrigger()
        }
      }
      steps {
        deleteDir()
        gitCheckout(basedir: "${BASE_DIR}", shallow: true, depth: 1)
        /*
        gitCheckout(basedir: "${BASE_DIR}", branch: "${params.branch_specifier}",
          repo: "git@github.com:elastic/${env.REPO}.git",
          credentialsId: "${JOB_GIT_CREDENTIALS}",
          githubNotifyFirstTimeContributor: false,
          reference: "/var/lib/jenkins/${env.REPO}.git")
        */
        dir("${BASE_DIR}"){
          matcher()
        }
      }
      post {
        cleanup {
          deleteDir()
        }
      }
    }
  }
}

def matcher(){
  echo "Checking command... '${env.GITHUB_COMMENT}'"
  switch ("${env.GITHUB_COMMENT}".toLowerCase()) {
    case ~/\/approve/:
      approve()
      break
    case ~/\/assign.*/:
      assign()
      break
    case ~/\/build/:
      build()
      break
    case ~/\/cc.*/:
      ccCmd()
      break
    case ~/\/close/:
      closeCmd()
      break
    case ~/\/deploy/:
      deploy()
      break
    case ~/\/run/:
      runCmd()
      break
    case ~/\/test/:
      test()
      break
    case ~/\/lgtm/:
      lgtm()
      break
    case ~/\/help/:
      help()
      break
    case ~/\/hold.*/:
      hold()
      break
    case ~/\/label.*/:
      labelCmd()
      break
    case ~/\/lint/:
      lint()
      break
    case ~/\/meow/:
      meow()
      break
    case ~/\/ok-to-test/:
      okToTest()
      break
    case ~/\/package/:
      packageCmd()
      break
    case ~/\/reopen/:
      reopenCmd()
      break
    case ~/\/skip.*/:
      skipCmd()
      break
    case ~/\/woof/:
    case ~/\/bark/:
      woof()
      break
    case ~/\/this-is-.*/:
      thisIs()
      break
    default:
      echo "Unrecognized command... '${env.GITHUB_COMMENT}'"
  }
}

def approve(){
  echo "${env.GITHUB_COMMENT}"
  //TODO not implemented in https://github.com/jenkinsci/pipeline-github-plugin
  // see https://github.com/jenkinsci/pipeline-github-plugin/pull/37
  // pullRequest.review('APPROVE')
  echo "Unsupported"
}

def assign(){
  echo "${env.GITHUB_COMMENT}"
  def usr = "${env.GITHUB_COMMENT}"
  usr -= 'cancel'
  usr -= '/assign'
  usr -= '@'
  usr = usr.trim()
  if(GITHUB_COMMENT.contains('cancel')){
    pullRequest.removeAssignees([usr])
  } else {
    pullRequest.addAssignees([usr])
  }
}

def build(){
  echo "${env.GITHUB_COMMENT}"
  build(job: 'apm-shared/apm-pipeline-library-mbp/main',
    parameters: [
      string(name: 'MAVEN_CONFIG', value: ''),
      booleanParam(name: 'make_release', value: false)
    ],
    propagate: false,
    quietPeriod: 10,
    wait: false
  )
}

def ccCmd(){
  echo "${env.GITHUB_COMMENT}"
  def usr = "${env.GITHUB_COMMENT}"
  usr -= 'cancel'
  usr -= '/cc'
  usr -= '@'
  usr = usr.trim()
  if(GITHUB_COMMENT.contains('cancel')){
    pullRequest.deleteReviewRequests([usr])
  } else {
    pullRequest.createReviewRequests([usr])
  }
}

def closeCmd(){
  echo "${env.GITHUB_COMMENT}"
  pullRequest.setState('closed')
}

def deploy(){
  echo "${env.GITHUB_COMMENT}"
}

def help(){
  echo "${env.GITHUB_COMMENT}"
  def body = """
  # ChatOps commands Help
  * **/approve** - Approve the PR.
  * **/assign [cancel] @Someone** - Assign the PR to Someone.
  * **/bark** - Add a dog image to the issue or PR.
  * **/build** - Launch the build process.
  * **/cc [cancel] @Someone** - Requests a review from the user(s).
  * **/close** - Close the PR.
  * **/deploy** - Launch the deploy process.
  * **/help** - Punt a comment with the ChatOps commands
  * **/hold [cancel]** - Adds or removes the `do-not-merge/hold` Label which is used to indicate that the PR should not be automatically merged.
  * **/label [cancel]** labelName - Adds a label to the PR
  * **/lgtm [cancel]** - Adds or removes the 'lgtm' label which is typically used to gate merging.
  * **/lint** - Launch the linting process.
  * **/meow** - Add a cat image to the issue or PR.
  * **/ok-to-test** - Marks a PR as 'trusted' and starts tests.
  * **/package** - Launch the packaging process.
  * **/reopen** - Reopen the PR.
  * **/run [something]** - Launch the "something" process
  * **/skip [cancel]** - Mark the PR to be skipped from build in CI.
  * **/test** - Launch the test process.
  * **/this-is-{fine|not-fine|unbearable}** - Add a reaction image to the PR.
  * **/woof** - Add a dog image to the issue or PR.
  """
  pullRequest.comment(body)
}

def hold(){
  echo "${env.GITHUB_COMMENT}"
  if(env.GITHUB_COMMENT.contains('cancel')){
    pullRequest.removeLabel("DO-NO-MERGGE")
  } else {
    pullRequest.addLabels(["DO-NO-MERGGE"])
  }
}

def lgtm(){
  echo "${env.GITHUB_COMMENT}"
  pullRequest.addLabels(["LGTM"])
}

def lint(){
  echo "${env.GITHUB_COMMENT}"
}

def meow(){
  echo "${env.GITHUB_COMMENT}"
  def body = """
  ![image](https://media.giphy.com/media/v6aOjy0Qo1fIA/giphy.gif)
  """
  pullRequest.comment(body)
}

def labelCmd(){
  echo "${env.GITHUB_COMMENT}"
  def label = "${env.GITHUB_COMMENT}"
  label -= 'cancel'
  label -= '/label'
  label = label.trim()
  if(env.GITHUB_COMMENT.contains('cancel')){
    pullRequest.removeLabel(label)
  } else {
    pullRequest.addLabels([label])
  }
}

def okToTest(){
  echo "${env.GITHUB_COMMENT}"
  if(env.GITHUB_COMMENT.contains('cancel')){
    pullRequest.removeLabel("OK-TO-TEST")
  } else {
    pullRequest.addLabels(["OK-TO-TEST"])
  }
}

def packageCmd(){
  echo "${env.GITHUB_COMMENT}"
}

def reopenCmd(){
  echo "${env.GITHUB_COMMENT}"
  // It does not work because closed jobs are not triggered by comments.
  echo "Unsupported"
}

def runCmd(){
  echo "${env.GITHUB_COMMENT}"
}

def skipCmd(){
  echo "${env.GITHUB_COMMENT}"
  if(env.GITHUB_COMMENT.contains('cancel')){
    pullRequest.removeLabel("SKIP-CI")
  } else {
    pullRequest.addLabels(["SKIP-CI"])
  }
}

def test(){
  echo "${env.GITHUB_COMMENT}"
}

def thisIs(){
  echo "${env.GITHUB_COMMENT}"
  def body = """
  ![image](https://media.giphy.com/media/v6aOjy0Qo1fIA/giphy.gif)
  """
  pullRequest.comment(body)
}

def woof(){
  echo "${env.GITHUB_COMMENT}"
  def body = """
  ![image](https://media.giphy.com/media/4Zo41lhzKt6iZ8xff9/giphy.gif)
  """
  pullRequest.comment(body)
}
