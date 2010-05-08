/*
 Copyright (c) 2008 thePlatform, Inc.

This file is part of Cuanto, a test results repository and analysis program.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Lesser General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Lesser General Public License for more details.

You should have received a copy of the GNU Lesser General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.

*/

package cuanto

import grails.util.GrailsUtil
import org.hibernate.SessionFactory

class InitializationService {

	def grailsApplication
	def dataService
	def testOutcomeService
	def testRunService
	boolean transactional = false
	SessionFactory sessionFactory

	void initTestResults() {
		if (TestResult.list().size() <= 0) {
			// set up the base TestResult types
			def resultList = []
			TestResult run = new TestResult(name: "Pass", includeInCalculations: true, isFailure: false)
			resultList += run

			TestResult fail = new TestResult(name: "Fail", includeInCalculations: true, isFailure: true)
			resultList += fail

			TestResult error = new TestResult(name: "Error", includeInCalculations: true, isFailure: true)
			resultList += error

			TestResult ignore = new TestResult(name: "Ignore", includeInCalculations: false, isFailure: false)
			resultList += ignore

			TestResult skip = new TestResult(name: "Skip", includeInCalculations: true, isFailure: true)
			resultList += skip

			TestResult unexecuted = new TestResult(name: "Unexecuted", includeInCalculations: false, isFailure: false)
			resultList += unexecuted

			resultList.each {result ->
				if (!result.save()) {
					result.errors.allErrors.each {
						log.warning it.toString()
					}
				}
			}
		} else {
			TestResult skip = TestResult.findByName("Skip")
			if (!(skip.includeInCalculations && skip.isFailure)) {
				log.info "Updating TestResult 'Skip' to be included in calculations and considered a failure"
				skip.includeInCalculations = true
				skip.isFailure = true
				dataService.saveDomainObject skip
			}
		}
	}


	void initAnalysisStates() {
		if (AnalysisState.list().size() <= 0) {
			def analysisList = []

			analysisList << new AnalysisState(name: "Unanalyzed", isAnalyzed: false, isDefault: true, isBug: false)
			analysisList << new AnalysisState(name: "Bug", isAnalyzed: true, isDefault: false, isBug: true)
			analysisList << new AnalysisState(name: "Environment", isAnalyzed: true, isDefault: false, isBug: false)
			analysisList << new AnalysisState(name: "Harness", isAnalyzed: true, isDefault: false, isBug: false)
			analysisList << new AnalysisState(name: "No Repro", isAnalyzed: true, isDefault: false, isBug: false)
			analysisList << new AnalysisState(name: "Other", isAnalyzed: true, isDefault: false, isBug: false)
			analysisList << new AnalysisState(name: "Test Bug", isAnalyzed: true, isDefault: false, isBug: false)
			analysisList << new AnalysisState(name: "Investigate", isAnalyzed: false, isDefault: false, isBug: false)

			analysisList.each {analysis ->
				if (!analysis.save()) {
					analysis.errors.allErrors.each {
						log.warning it.toString()
					}
				}
			}
		}
	}


	void initTestTypes() {
		if (TestType.list().size() <= 0) {
			def typeList = []

			typeList += new TestType(name: "JUnit")
			typeList += new TestType(name: "TestNG")
			typeList += new TestType(name: "NUnit")
			typeList += new TestType(name: "Manual")

			typeList.each {tp ->
				if (!tp.save()) {
					tp.errors.allErrors.each {
						log.warning it.toString()
					}
				}
			}
		}

		if (!TestType.findByNameIlike("NUnit")) {
			dataService.saveDomainObject(new TestType(name: "NUnit"))
		}
	}

	void initProjects() {
		if (GrailsUtil.environment == "development") {
			if (!Project.findByName("CuantoProd")) {
				def grp = new ProjectGroup(name: "Sample").save()
				new Project(name: "CuantoProd", projectKey: "CUANTO", projectGroup: grp,
					bugUrlPattern: "http://tpjira/browse/{BUG}", testType: TestType.findByName("JUnit")).save()
			}
			if (!Project.findByName("CuantoNG")) {
				def grp = ProjectGroup.findByName("Sample")
				new Project(name: "CuantoNG", projectKey: "CNG", projectGroup: grp,
					bugUrlPattern: "http://tpjira/browse/{BUG}", testType: TestType.findByName("TestNG")).save()
			}
			if (grailsApplication.config.dataSource.lotsOfExtraProjects)
				createLotsOfExtraProjects()
		}
	}

	void initIsFailureStatusChanged() {
		def numInitialized = 0
		def numToInit = TestOutcome.countByIsFailureStatusChangedIsNull()
		if (numToInit > 0)
			log.info "Initializing TestOutcomes where isFailureStatusChanged = null... count = $numToInit"

		def testOutcomes = TestOutcome.findAllByIsFailureStatusChangedIsNull([offset: 0, max: 100])
		while (testOutcomes.size() > 0) {
			for (TestOutcome testOutcome: testOutcomes)
				testOutcome.isFailureStatusChanged = testOutcomeService.isFailureStatusChanged(testOutcome)

			dataService.saveTestOutcomes(testOutcomes)
			numInitialized += testOutcomes.size()
			testOutcomes = TestOutcome.findAllByIsFailureStatusChangedIsNull([offset: 0, max: 100])

			if (numInitialized % 1000 == 0) {
				log.info "Initialized ${numInitialized} TestOutcomes."
				sessionFactory.currentSession.flush()
			}
		}
		if (numInitialized > 0)
			log.info "Finished initializing ${numInitialized} TestOutcomes."
	}

	void initTestRunStats() {
		def testRunsToUpdate = getTestRunsWithNullNewFailures()
		while (testRunsToUpdate.size() > 0) {
			for (TestRun testRun: testRunsToUpdate) {
				// without setting testRun.testRunStatistics.newFailures, a subsequent query for the TestRun
				// returns the TestRun with testRunStatistics.newFailures = null!
				// hibernate cache issue? but i'm not even calling save on testRun, here.
				testRun.testRunStatistics.newFailures = getNewFailuresCount(testRun)
				TestRunStats.executeUpdate("update TestRunStats stats set stats.newFailures = ? where stats.id = ?",
					[testRun.testRunStatistics.newFailures, testRun.testRunStatistics.id])
				log.info "Initialized TestRun #${testRun.id} for Project [${testRun.project.name}]."
			}
			testRunsToUpdate = getTestRunsWithNullNewFailures()
		}
	}

	void createLotsOfExtraProjects() {
		def rnd = new Random()
		30.times { grpIndex ->
			def grp = new ProjectGroup(name: "Sample$grpIndex").save()
			(rnd.nextInt(9) + 1).times { prjIndex ->
				if (!Project.findByName("CuantoProd$grpIndex-$prjIndex")) {
					new Project(name: "CuantoProd$grpIndex-$prjIndex", projectKey: "CUANTO$grpIndex-$prjIndex", projectGroup: grp,
						bugUrlPattern: "http://tpjira/browse/{BUG}", testType: TestType.findByName("JUnit")).save()
				}
			}
		}

		50.times {
			// create ungrouped projects
			if (!Project.findByName("Ungrouped-$it")) {
				new Project(name: "Ungrouped-$it", projectKey: "Ungrouped-$it",
					bugUrlPattern: "http://tpjira/browse/{BUG}", testType: TestType.findByName("JUnit")).save()
			}
		}
	}

	List<TestRun> getTestRunsWithNullNewFailures() {
		return TestRun.createCriteria().list {
			testRunStatistics {
				isNull('newFailures')
			}
			maxResults(100)
		}
	}

	Integer getNewFailuresCount(TestRun testRun) {
		return TestOutcome.createCriteria().count {
			and {
				eq('testRun', testRun)
				eq('isFailureStatusChanged', true)
				testResult {
					eq('isFailure', true)
				}
			}
		}
	}

	void initializeAll() {
		initTestResults()
		initAnalysisStates()
		initTestTypes()
		initProjects()
		initIsFailureStatusChanged()
		initTestRunStats()
	}
}
