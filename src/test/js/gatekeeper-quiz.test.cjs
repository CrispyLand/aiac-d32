const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const template = fs.readFileSync(path.join(__dirname, '../../main/resources/templates/gatekeeper.html'), 'utf8');
const script = template.slice(template.indexOf('    let activeQuizData ='), template.indexOf('    // ── Paste blocking'));

function quizUI(fetch) {
    const elements = new Map();
    const statuses = [];
    const context = vm.createContext({
        fetch, console, sessionId: 'session-test',
        showStatus: (...args) => statuses.push(args),
        document: {
            getElementById: id => {
                if (!elements.has(id)) elements.set(id, {
                    innerHTML: '', textContent: '', disabled: false,
                    classList: { add() {}, remove() {} }
                });
                return elements.get(id);
            },
            querySelectorAll: () => [],
            createElement: () => ({
                set textContent(value) { this.innerHTML = String(value).replace(/</g, '&lt;'); },
                innerHTML: ''
            })
        }
    });
    vm.runInContext(script, context);
    return { context, elements, statuses, run: code => vm.runInContext(code, context) };
}

const mc = { id: 'q1', type: 'multiple_choice', stem: 'Question?', options: [{ key: 'b', text: 'Answer' }] };
const response = (body, ok = true) => ({ ok, json: async () => body });

test('failed final submission retries the same answers without advancing or duplicating them', async () => {
    const submissions = [];
    let failures = 1;
    const ui = quizUI(async (url, request) => {
        if (url.endsWith('/start')) return response({ quizId: 'quiz-one', questions: [mc], trigger: 'remedial' });
        submissions.push(JSON.parse(request.body));
        if (failures-- > 0) throw new Error('Connection lost');
        return response({ score: 1, total: 1, results: [{ correct: true, stem: 'Question?', explanation: 'Because' }] });
    });
    await ui.run("startQuiz('remedial')");
    ui.run("selectedQuizAnswer = 'b'; quizNext()");
    await new Promise(resolve => setImmediate(resolve));
    const button = ui.elements.get('quizNextBtn');
    assert.equal(button.textContent, 'Retry submission');
    assert.equal(button.disabled, false);
    await button.onclick();
    assert.deepEqual(submissions[0], submissions[1]);
    assert.equal(submissions[1].answers.length, 1);
    assert.match(ui.elements.get('quizModal').innerHTML, /Quiz Results/);
});

test('matching answers preserve commas inside labels', async () => {
    let submitted;
    const ui = quizUI(async (url, request) => {
        submitted = JSON.parse(request.body);
        return response({ score: 1, total: 1, results: [] });
    });
    ui.context.document.querySelectorAll = () => [{ value: 'Cost, price and profit' }, { value: 'Customers' }];
    ui.run(`activeQuizData = { quizId: 'quiz-match', questions: [{id:'match',type:'matching'}], currentIndex:0, answers:[], trigger:'remedial' }; quizNext()`);
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(JSON.parse(submitted.answers[0].answer), ['Cost, price and profit', 'Customers']);
});

test('overlapping quiz starts send one request and a skipped quiz can be reopened', async () => {
    let calls = 0;
    let release;
    const pending = new Promise(resolve => { release = resolve; });
    const ui = quizUI(async () => {
        calls++;
        await pending;
        return response({ quizId: 'quiz-one', questions: [mc] });
    });
    const first = ui.run("startQuiz('reinforcement')");
    await ui.run("startQuiz('reinforcement')");
    assert.equal(calls, 1);
    release();
    await first;
    ui.run('skipQuiz()');
    await ui.run("startQuiz('reinforcement')");
    assert.equal(calls, 2);
});

test('all template scripts parse as JavaScript', () => {
    for (const name of ['gatekeeper.html', 'gatekeeper-teacher.html']) {
        const source = fs.readFileSync(path.join(__dirname, '../../main/resources/templates', name), 'utf8');
        for (const match of source.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)) {
            new vm.Script(match[1], { filename: name });
        }
    }
});
