// test-scheduler-uaf-regression.cpp
//
// Regression tests for the use-after-free in Scheduler::execute() that occurs
// when a callback destroys the task that the pre-captured next-task pointer
// refers to.
//
// Root cause
// ----------
// execute() pre-captures `nextTask = iCurrent->iNext` before invoking the
// callback.  If that callback destroys the heap-allocated object that nextTask
// points to (e.g. a shared_ptr<Connection> ref-count reaching zero, which calls
// ~Task() on value-member tasks, which calls deleteTask()), `nextTask` becomes
// a dangling pointer.  On targets with heap poisoning (ESP-IDF) this manifests
// as a LoadProhibited crash at EXCVADDR 0xbaad567c.
//
// Fix
// ---
// Promote `nextTask` from a local variable in execute() to the scheduler member
// `iNextExecute`, and have deleteTask() advance it when it is about to unlink
// the task that iNextExecute points to.  This works because deleteTask() runs
// inside the destructor *before* the memory is freed, so aTask.iNext is still
// readable.  The update cascades naturally through chain destructions.
//
// Test scenarios
// --------------
// 1. Core regression: A -> B -> C, A's callback deletes B.  C must still run.
// 2. Longer chain:    A -> B -> C -> D, A's callback deletes B.  C and D run.
// 3. Chain deletion:  A -> B -> C -> D, A's callback deletes B and C.  D runs.
// 4. Last-task deletion: A -> B, A's callback deletes B (iLast).  No crash.
// 5. Self-deletion:   A -> B -> C, B's callback deletes itself.  C must run.

#include <gtest/gtest.h>
#include "Arduino.h"
#include "TaskScheduler.h"

std::vector<std::string> test_output;

// ---------------------------------------------------------------------------
// Callback state -- plain function pointers for maximum portability
// (TaskScheduler does not use std::function without _TASK_STD_FUNCTION)
// ---------------------------------------------------------------------------

static int   s_a_count = 0;
static int   s_b_count = 0;
static int   s_c_count = 0;
static int   s_d_count = 0;

static Task* s_victim1 = nullptr;   // task(s) to be destroyed mid-callback
static Task* s_victim2 = nullptr;

// A's callback destroys victim1
static void cbA_delete_one() {
    s_a_count++;
    delete s_victim1;
    s_victim1 = nullptr;
}

// A's callback destroys victim1 and victim2
static void cbA_delete_two() {
    s_a_count++;
    delete s_victim1;
    s_victim1 = nullptr;
    delete s_victim2;
    s_victim2 = nullptr;
}

// B's callback destroys itself (s_victim1 must point to B)
static void cbB_delete_self() {
    s_b_count++;
    delete s_victim1;
    s_victim1 = nullptr;
}

static void cbA() { s_a_count++; }
static void cbB() { s_b_count++; }
static void cbC() { s_c_count++; }
static void cbD() { s_d_count++; }

// ---------------------------------------------------------------------------

class UafRegressionTest : public ::testing::Test {
protected:
    void SetUp() override {
        s_a_count = s_b_count = s_c_count = s_d_count = 0;
        s_victim1 = nullptr;
        s_victim2 = nullptr;
        test_output.clear();
    }
    void TearDown() override {
        delete s_victim1;
        s_victim1 = nullptr;
        delete s_victim2;
        s_victim2 = nullptr;
        test_output.clear();
    }
};

// ---------------------------------------------------------------------------
// 1. Core regression: task immediately after the deleted task must still run.
//    Chain: A -> B -> C.  A deletes B.  C must execute.
// ---------------------------------------------------------------------------
TEST_F(UafRegressionTest, TaskAfterDeletedTaskStillRuns) {
    Scheduler ts;

    Task taskA(TASK_IMMEDIATE, TASK_ONCE, cbA_delete_one, &ts, true);
    s_victim1 = new Task(TASK_IMMEDIATE, TASK_ONCE, cbB, &ts, true);
    Task taskC(TASK_IMMEDIATE, TASK_ONCE, cbC, &ts, true);

    ts.execute();

    EXPECT_EQ(s_a_count, 1) << "Task A should have run";
    EXPECT_EQ(s_b_count, 0) << "Task B was deleted by A before it could run";
    EXPECT_EQ(s_c_count, 1) << "Task C must run despite B being deleted mid-iteration";
}

// ---------------------------------------------------------------------------
// 2. All tasks beyond the deleted node must run in the same pass.
//    Chain: A -> B -> C -> D.  A deletes B.  C and D must execute.
// ---------------------------------------------------------------------------
TEST_F(UafRegressionTest, AllTasksAfterDeletedTaskStillRun) {
    Scheduler ts;

    Task taskA(TASK_IMMEDIATE, TASK_ONCE, cbA_delete_one, &ts, true);
    s_victim1 = new Task(TASK_IMMEDIATE, TASK_ONCE, cbB, &ts, true);
    Task taskC(TASK_IMMEDIATE, TASK_ONCE, cbC, &ts, true);
    Task taskD(TASK_IMMEDIATE, TASK_ONCE, cbD, &ts, true);

    ts.execute();

    EXPECT_EQ(s_a_count, 1) << "A ran";
    EXPECT_EQ(s_b_count, 0) << "B was deleted";
    EXPECT_EQ(s_c_count, 1) << "C must run";
    EXPECT_EQ(s_d_count, 1) << "D must run";
}

// ---------------------------------------------------------------------------
// 3. Two consecutive tasks destroyed in a single callback.
//    Chain: A -> B -> C -> D.  A deletes B and C.  D must execute.
//    This simulates a connection object owning two value-member tasks.
// ---------------------------------------------------------------------------
TEST_F(UafRegressionTest, TwoConsecutiveTasksDeletedDuringCallback) {
    Scheduler ts;

    Task taskA(TASK_IMMEDIATE, TASK_ONCE, cbA_delete_two, &ts, true);
    s_victim1 = new Task(TASK_IMMEDIATE, TASK_ONCE, cbB, &ts, true);
    s_victim2 = new Task(TASK_IMMEDIATE, TASK_ONCE, cbC, &ts, true);
    Task taskD(TASK_IMMEDIATE, TASK_ONCE, cbD, &ts, true);

    ts.execute();

    EXPECT_EQ(s_a_count, 1) << "A ran";
    EXPECT_EQ(s_b_count, 0) << "B was deleted";
    EXPECT_EQ(s_c_count, 0) << "C was deleted";
    EXPECT_EQ(s_d_count, 1) << "D must run after both B and C were destroyed";
}

// ---------------------------------------------------------------------------
// 4. Deleting the last task in the chain (iLast).
//    Chain: A -> B.  A deletes B.  Scheduler must not crash; loop ends cleanly.
// ---------------------------------------------------------------------------
TEST_F(UafRegressionTest, DeletingLastTaskInChain) {
    Scheduler ts;

    Task taskA(TASK_IMMEDIATE, TASK_ONCE, cbA_delete_one, &ts, true);
    s_victim1 = new Task(TASK_IMMEDIATE, TASK_ONCE, cbB, &ts, true);

    ts.execute();

    EXPECT_EQ(s_a_count, 1) << "A ran";
    EXPECT_EQ(s_b_count, 0) << "B was deleted before it could run";
    // Main assertion: no crash.  If we got here the loop exited cleanly.
}

// ---------------------------------------------------------------------------
// 5. Task deletes itself during its own callback.
//    Chain: A -> B -> C.  B's callback deletes B (self-deletion).
//    A and C must still run normally.
// ---------------------------------------------------------------------------
TEST_F(UafRegressionTest, TaskDeletesItselfDuringCallback) {
    Scheduler ts;

    Task taskA(TASK_IMMEDIATE, TASK_ONCE, cbA, &ts, true);
    s_victim1 = new Task(TASK_IMMEDIATE, TASK_ONCE, cbB_delete_self, &ts, true);
    Task taskC(TASK_IMMEDIATE, TASK_ONCE, cbC, &ts, true);

    ts.execute();

    EXPECT_EQ(s_a_count, 1) << "A ran normally";
    EXPECT_EQ(s_b_count, 1) << "B ran and then deleted itself";
    EXPECT_EQ(s_c_count, 1) << "C must run after B's self-deletion";
}

// ---------------------------------------------------------------------------

int main(int argc, char **argv) {
    ::testing::InitGoogleTest(&argc, argv);
    return RUN_ALL_TESTS();
}
