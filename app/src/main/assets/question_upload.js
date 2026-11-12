'use strict';

function parseUploadedQuestions(text) {
  const rows = [];
  const answerKey = new Map();
  let row;
  let field;
  const normalized = text.normalize('NFKC').replace(/\r/g, '')
    .replace(/\*\*|`/g, '')
    .replace(/(\S)[^\S\n]+(?=(?:正确答案|参考答案|答案|解析)\s*:)/g, '$1\n');
  const lines = normalized.split('\n').flatMap(line => {
    if (/^\s*(?:正确答案|参考答案|答案|解析)\s*:/.test(line)) return [line];
    return line.replace(/(\S)[^\S\n]+(?=[A-D][.、:)])/g, '$1\n').split('\n');
  });

  for (const raw of lines) {
    const line = raw.trim();
    if (!line) continue;
    const question = line.match(/^(?:第\s*(\d+)\s*题\s*[:.、]?|(\d+)\s*[.、)])\s*(.+)$/);
    const option = line.match(/^([A-D])\s*[.、:)]\s*(.+)$/i);
    const answer = line.match(/^(?:正确答案|参考答案|答案)\s*:\s*(.*)$/);
    const explanation = line.match(/^解析\s*:\s*(.*)$/);
    if (question) {
      row = {number: Number(question[1] || question[2]), question: question[3], options: Array(4).fill(''), answerIndex: -1, explanation: ''};
      rows.push(row);
      field = 'question';
    } else if (option && row) {
      field = option[1].toUpperCase().charCodeAt(0) - 65;
      row.options[field] = option[2];
    } else if (answer) {
      const key = [...answer[1].matchAll(/(\d+)\s*[.、:)]?\s*([A-D])\b/gi)];
      if (key.length) {
        for (const entry of key) answerKey.set(Number(entry[1]), entry[2].toUpperCase().charCodeAt(0) - 65);
      } else if (row) {
        const letter = answer[1].match(/^([A-D])(?:$|[\s.、:)])/i);
        const index = letter ? letter[1].toUpperCase().charCodeAt(0) - 65 : row.options.indexOf(answer[1]);
        row.answerIndex = answer[1] ? index : -1;
      }
      field = null;
    } else if (explanation && row) {
      row.explanation = explanation[1];
      field = 'explanation';
    } else if (!row && !option && !explanation) {
      row = {number: 1, question: line, options: Array(4).fill(''), answerIndex: -1, explanation: ''};
      rows.push(row);
      field = 'question';
    } else if (row && field === 'question') {
      row.question += ' ' + line;
    }
  }
  return rows.map(({number, ...draft}) => ({
    ...draft,
    answerIndex: answerKey.get(number) ?? draft.answerIndex
  }));
}

function suggestArithmeticAnswer(row) {
  const match = row.question.match(/^(-?\d{1,6})\s*([+\-−×*÷/])\s*(-?\d{1,6})\s*=\s*\??$/);
  if (!match) return -1;
  const a = Number(match[1]);
  const b = Number(match[3]);
  const results = {'+': a + b, '-': a - b, '−': a - b, '×': a * b, '*': a * b, '÷': a / b, '/': a / b};
  const result = results[match[2]];
  return Number.isFinite(result) ? row.options.findIndex(option => option.trim() && Number(option) === result) : -1;
}

function initQuestionUpload(settings) {
  const byId = id => document.getElementById(id);
  const grade = byId('uploadGrade');
  const term = byId('uploadTerm');
  const difficulty = byId('uploadDifficulty');
  const subject = byId('uploadSubject');
  const input = byId('questionText');
  const preview = byId('questionPreview');
  const save = byId('saveQuestions');
  const previewButton = byId('previewQuestions');
  const photoButtons = [byId('takePhoto'), byId('choosePhoto')];
  const status = byId('photoMessage');
  const busyControls = [input, previewButton, save, ...photoButtons];
  const setBusy = busy => busyControls.forEach(control => { control.disabled = busy; });
  let prepared = [];

  const grades = ['按年龄', ...Array.from({length: 9}, (_, i) => (i + 1) + '年级'), '幼儿园大班'];
  const levels = ['不限', '入门', '基础', '中等', '提高', '挑战'];
  grades.forEach((label, index) => grade.add(new Option(label, index)));
  levels.forEach((label, index) => difficulty.add(new Option(label, index)));
  grade.value = settings.grade ?? 0;
  term.value = settings.term ?? 0;
  difficulty.value = settings.difficulty ?? 0;
  subject.value = settings.categories?.length === 1 ? settings.categories[0] : '';

  function updateClassification() {
    term.disabled = grade.value === '0';
    if (term.disabled) term.value = '0';
    byId('classification').textContent = [
      grade.selectedOptions[0].text, term.value !== '0' && term.selectedOptions[0].text,
      subject.value.trim() || '待选科目', difficulty.value !== '0' && difficulty.selectedOptions[0].text
    ].filter(Boolean).join(' · ');
  }
  for (const field of [grade, term, subject, difficulty]) field.oninput = updateClassification;
  updateClassification();

  function clearPreview() {
    prepared = [];
    preview.replaceChildren();
    save.classList.add('hidden');
  }

  function showPreview() {
    clearPreview();
    if (input.value.length > 80000) {
      status.textContent = '文字太多，请分几次上传。';
      return;
    }
    const rows = parseUploadedQuestions(input.value);
    if (!rows.length) {
      status.textContent = '没有识别到题目。请拍清题干和选项，或粘贴带 A–D 选项的题目。';
      return;
    }
    if (rows.length > 100) {
      status.textContent = '一次最多预览 100 题，请分批上传。';
      return;
    }
    rows.forEach((row, index) => {
      const panel = document.createElement('div');
      panel.className = 'field';
      const label = document.createElement('label');
      label.textContent = (index + 1) + '. ' + row.question;
      panel.append(label);
      if (row.options.some(option => !option) || new Set(row.options).size !== 4) {
        label.append('（缺少四个不同选项，请编辑上方文字；本题会跳过）');
      } else {
        const choice = document.createElement('select');
        choice.add(new Option(row.answerIndex < 0 ? '待确认，暂不导入' : '跳过本题', '-1'));
        const suggestion = row.answerIndex < 0 ? suggestArithmeticAnswer(row) : -1;
        row.options.forEach((option, i) => {
          choice.add(new Option('ABCD'[i] + '. ' + option + (i === suggestion ? '（口算建议）' : ''), i));
        });
        choice.value = row.answerIndex;
        label.append(choice);
        prepared.push({row, choice});
      }
      preview.append(panel);
    });
    save.classList.toggle('hidden', !prepared.length);
    status.textContent = '识别到 ' + rows.length + ' 题，请核对选项和答案。未选择答案的题会跳过。';
  }

  previewButton.onclick = showPreview;
  input.oninput = clearPreview;

  byId('copyDoubao').onclick = async () => {
    const gradeLabel = grade.value === '0' ? settings.age + '岁' : grade.selectedOptions[0].text;
    const volume = term.value === '0' ? '' : term.selectedOptions[0].text;
    const level = difficulty.value === '0' ? '基础' : difficulty.selectedOptions[0].text;
    const prompt = '请为' + gradeLabel + volume + '编写 10 道' + (subject.value.trim() || '综合') +
      '四选一练习题，难度为' + level + '。每题用“1. 题干”这样的连续编号开头，A. B. C. D. 四个选项分别独占一行，随后另起两行写“答案：B”和“解析：一句话解释”。每题只有一个正确答案，只输出题目。';
    try {
      await navigator.clipboard.writeText(prompt);
      status.textContent = '已复制。发给豆包，再把生成的题目贴回来。';
    } catch (_) {
      window.prompt('长按复制出题指令', prompt);
    }
  };

  async function readPhoto(file) {
    if (!file) return;
    if (file.size > 20 * 1024 * 1024) {
      status.textContent = '照片超过 20 MB，请裁剪后再选。';
      return;
    }
    setBusy(true);
    status.textContent = '电视正在识别照片…';
    const url = URL.createObjectURL(file);
    try {
      const image = new Image();
      image.src = url;
      await image.decode();
      const scale = Math.min(1, 2000 / Math.max(image.width, image.height));
      const canvas = document.createElement('canvas');
      canvas.width = Math.max(1, Math.round(image.width * scale));
      canvas.height = Math.max(1, Math.round(image.height * scale));
      const drawing = canvas.getContext('2d', {alpha: false});
      drawing.fillStyle = '#fff';
      drawing.fillRect(0, 0, canvas.width, canvas.height);
      drawing.drawImage(image, 0, 0, canvas.width, canvas.height);
      const body = canvas.toDataURL('image/jpeg', 0.85).split(',')[1];
      const response = await fetch('/api/questions/photo', {method: 'POST', body});
      const result = await response.json();
      if (!response.ok) throw new Error(result.error || '识别失败');
      if (!result.text?.trim()) throw new Error('照片里没有识别到文字，请靠近拍清题目。');
      input.value = result.text;
      showPreview();
    } catch (error) {
      status.textContent = '照片处理失败。' + error.message + ' 可换一张照片，或粘贴题目文字。';
    } finally {
      URL.revokeObjectURL(url);
      setBusy(false);
    }
  }

  [byId('cameraFile'), byId('photoFile')].forEach((fileInput, index) => {
    photoButtons[index].onclick = () => fileInput.click();
    fileInput.onchange = () => {
      const file = fileInput.files[0];
      fileInput.value = '';
      readPhoto(file);
    };
  });

  save.onclick = async () => {
    const category = subject.value.trim();
    if (!category) {
      status.textContent = '请选择这批题的科目。';
      subject.closest('details').open = true;
      subject.focus();
      return;
    }
    const questions = prepared.filter(({choice}) => choice.value !== '-1').map(({row, choice}) => ({
      ...row, age: settings.age, grade: Number(grade.value), term: Number(term.value),
      category, difficulty: Number(difficulty.value), answerIndex: Number(choice.value)
    }));
    if (!questions.length) {
      status.textContent = '请至少确认一道题的正确答案。';
      return;
    }
    setBusy(true);
    try {
      const response = await fetch('/api/questions/import', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({mode: 'append', questions})
      });
      const result = await response.json();
      if (!response.ok || !result.ok) throw new Error(result.errors?.[0]?.reason || result.error || '导入失败');
      clearPreview();
      input.value = '';
      status.textContent = '已加入 ' + result.imported + ' 题，跳过 ' + result.skipped + ' 道重复题。';
    } catch (error) {
      status.textContent = '上传失败。' + error.message;
    } finally {
      setBusy(false);
    }
  };
}
