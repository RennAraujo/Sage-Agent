/*
 * Mini chat de teste do Sage.
 *
 * Regras que este arquivo segue de propósito (a CSP da aplicação só aceita scripts e estilos próprios):
 *  - todo texto vindo do servidor, do modelo ou do acervo entra na página só por textContent / createTextNode;
 *  - nenhuma string vira marcação HTML nem código executável, e não há atributo style nem on* em lugar nenhum;
 *  - a chave de API fica só no sessionStorage da aba e vai no cabeçalho Authorization;
 *  - o SSE é lido com fetch + ReadableStream, porque EventSource não deixa mandar cabeçalho.
 */
(function () {
  'use strict';

  var KEY_STORAGE = 'sage.apiKey';
  var VERSION_PREFIX = /^\s*documentação consultada\s*:/i;
  var FENCE = /```([^\n`]*)\n([\s\S]*?)(?:\n```|```|$)/g;
  var POLL_MS = 2000;
  var POLL_MAX = 300;

  // ---------- utilidades ----------

  function $(id) {
    return document.getElementById(id);
  }

  function h(tag, opts, children) {
    var node = document.createElement(tag);
    opts = opts || {};
    if (opts.cls) node.className = opts.cls;
    if (opts.text !== undefined && opts.text !== null) node.textContent = String(opts.text);
    if (opts.attrs) {
      Object.keys(opts.attrs).forEach(function (name) {
        node.setAttribute(name, opts.attrs[name]);
      });
    }
    (children || []).forEach(function (child) {
      if (child) node.appendChild(child);
    });
    return node;
  }

  function clear(node) {
    while (node.firstChild) node.removeChild(node.firstChild);
  }

  function readKey() {
    try {
      return window.sessionStorage.getItem(KEY_STORAGE) || '';
    } catch (e) {
      return '';
    }
  }

  function writeKey(value) {
    try {
      if (value) window.sessionStorage.setItem(KEY_STORAGE, value);
      else window.sessionStorage.removeItem(KEY_STORAGE);
      return true;
    } catch (e) {
      return false;
    }
  }

  function capitalize(text) {
    return text ? text.charAt(0).toUpperCase() + text.slice(1) : text;
  }

  function seconds(ms) {
    return (ms / 1000).toFixed(ms >= 10000 ? 0 : 1) + ' s';
  }

  function setBusy(button, busy) {
    button.disabled = busy;
  }

  // ---------- erros da API ----------

  var HINTS = {
    401: 'Informe uma chave de API válida no topo da página.',
    403: 'Essa chave não tem o papel necessário (documentos, lacunas e importação pedem EDITOR).',
    413: 'O arquivo é maior que o limite aceito.',
    415: 'Esse tipo de arquivo não é aceito.'
  };

  var FALLBACK_TITLES = {
    400: 'Requisição inválida',
    401: 'Autenticação necessária',
    403: 'Acesso negado',
    404: 'Não encontrado',
    409: 'Conflito',
    413: 'Arquivo grande demais',
    415: 'Tipo de arquivo não aceito',
    422: 'Documento recusado',
    429: 'Muitas requisições',
    500: 'Erro interno',
    503: 'Serviço indisponível'
  };

  function readProblem(response) {
    return response
      .text()
      .then(function (body) {
        try {
          var parsed = JSON.parse(body);
          return parsed && typeof parsed === 'object' ? parsed : {};
        } catch (e) {
          return {};
        }
      })
      .catch(function () {
        return {};
      })
      .then(function (problem) {
        var title =
          typeof problem.title === 'string' && problem.title ? problem.title : FALLBACK_TITLES[response.status] || 'Erro';
        var parts = [title + ' (' + response.status + ')'];
        if (typeof problem.detail === 'string' && problem.detail) parts.push(problem.detail);
        if (response.status === 429 || response.status === 503) {
          var retry = response.headers.get('Retry-After');
          if (retry && /^\d+$/.test(retry)) parts.push('Tente de novo em ' + retry + ' s');
        } else if (HINTS[response.status]) {
          parts.push(HINTS[response.status]);
        }
        var message = parts
          .map(function (part) {
            return part.replace(/[.\s]+$/, '');
          })
          .join('. ');
        return { status: response.status, message: message + '.', problem: problem };
      });
  }

  function api(path, options) {
    var key = readKey();
    if (!key) {
      return Promise.reject({ friendly: 'Informe a chave de API no topo da página.' });
    }
    options = options || {};
    var headers = Object.assign({}, options.headers || {}, { Authorization: 'Bearer ' + key });
    return fetch(
      path,
      Object.assign({}, options, { headers: headers, cache: 'no-store', referrerPolicy: 'no-referrer' })
    ).then(function (response) {
      if (response.status === 401) {
        writeKey('');
        keyStatus.textContent = 'Chave inválida ou expirada. Digite outra.';
      }
      return response;
    });
  }

  function errorText(error) {
    if (error && error.friendly) return error.friendly;
    if (error && error.name === 'AbortError') return 'Interrompido.';
    return 'Não consegui falar com o servidor. Ele está no ar?';
  }

  function showNote(container, kind, text) {
    clear(container);
    if (text) {
      container.appendChild(
        h('p', { cls: 'note ' + kind, text: text, attrs: { role: kind === 'error' ? 'alert' : 'status' } })
      );
    }
  }

  // ---------- abas ----------

  var tabs = Array.prototype.slice.call(document.querySelectorAll('[role="tab"]'));
  var docsLoaded = false;
  var gapsLoaded = false;

  function selectTab(tab, focus) {
    tabs.forEach(function (other) {
      var active = other === tab;
      other.setAttribute('aria-selected', active ? 'true' : 'false');
      other.tabIndex = active ? 0 : -1;
      $(other.getAttribute('aria-controls')).hidden = !active;
    });
    if (focus) tab.focus();
    if (tab.id === 'tab-documentos' && !docsLoaded && readKey()) loadDocs();
    if (tab.id === 'tab-lacunas' && !gapsLoaded && readKey()) loadGaps();
  }

  tabs.forEach(function (tab, index) {
    tab.addEventListener('click', function () {
      selectTab(tab, false);
    });
    tab.addEventListener('keydown', function (event) {
      var next = null;
      if (event.key === 'ArrowRight') next = tabs[(index + 1) % tabs.length];
      else if (event.key === 'ArrowLeft') next = tabs[(index + tabs.length - 1) % tabs.length];
      else if (event.key === 'Home') next = tabs[0];
      else if (event.key === 'End') next = tabs[tabs.length - 1];
      if (next) {
        event.preventDefault();
        selectTab(next, true);
      }
    });
  });

  // ---------- chave de API ----------

  var keyInput = $('api-key');
  var keyStatus = $('key-status');

  function refreshKeyStatus() {
    keyStatus.textContent = readKey() ? 'Chave guardada só nesta aba.' : 'Sem chave.';
  }

  $('key-form').addEventListener('submit', function (event) {
    event.preventDefault();
    var value = keyInput.value.trim();
    if (!value) {
      keyStatus.textContent = 'Digite a chave antes de guardar.';
      return;
    }
    if (writeKey(value)) refreshKeyStatus();
    else keyStatus.textContent = 'O navegador não deixou guardar a chave; ela vale só até recarregar.';
    keyInput.value = '';
    docsLoaded = false;
    gapsLoaded = false;
  });

  $('key-clear').addEventListener('click', function () {
    writeKey('');
    keyInput.value = '';
    refreshKeyStatus();
  });

  refreshKeyStatus();

  // ---------- SSE ----------

  /** Parser de Server-Sent Events que aguenta chunks cortados em qualquer ponto (inclusive no meio de um CRLF). */
  function createSseParser(onEvent) {
    var buffer = '';
    var eventName = 'message';
    var data = [];

    function dispatch() {
      if (data.length) onEvent(eventName, data.join('\n'));
      eventName = 'message';
      data = [];
    }

    function line(text) {
      if (text === '') {
        dispatch();
        return;
      }
      if (text.charAt(0) === ':') return;
      var colon = text.indexOf(':');
      var field = colon < 0 ? text : text.slice(0, colon);
      var value = colon < 0 ? '' : text.slice(colon + 1);
      if (value.charAt(0) === ' ') value = value.slice(1);
      if (field === 'event') eventName = value;
      else if (field === 'data') data.push(value);
    }

    return {
      push: function (chunk) {
        buffer += chunk;
        var start = 0;
        for (var i = 0; i < buffer.length; i++) {
          var c = buffer.charAt(i);
          if (c !== '\n' && c !== '\r') continue;
          if (c === '\r' && i === buffer.length - 1) break; // pode ser o começo de um CRLF
          line(buffer.slice(start, i));
          if (c === '\r' && buffer.charAt(i + 1) === '\n') i++;
          start = i + 1;
        }
        buffer = buffer.slice(start);
      },
      finish: function () {
        if (buffer) line(buffer.replace(/\r$/, ''));
        buffer = '';
        dispatch();
      }
    };
  }

  // ---------- Perguntar ----------

  var answers = $('answers');
  var askForm = $('ask-form');
  var questionInput = $('question');
  var askSend = $('ask-send');
  var askStop = $('ask-stop');
  var askStatus = $('ask-status');
  var conversationId = '';
  var controller = null;

  function appendParagraph(container, text) {
    var trimmed = text.trim();
    if (!trimmed) return;
    var p = h('p');
    trimmed.split(/(\[\d+\])/).forEach(function (part) {
      if (/^\[\d+\]$/.test(part)) p.appendChild(h('span', { cls: 'cite-mark', text: part }));
      else if (part) p.appendChild(document.createTextNode(part));
    });
    container.appendChild(p);
  }

  function appendCode(container, language, code) {
    var figure = h('figure', { cls: 'code', attrs: { 'aria-label': 'Bloco de código' } });
    if (language) figure.appendChild(h('figcaption', { text: language }));
    figure.appendChild(h('pre', {}, [h('code', { text: code })]));
    container.appendChild(figure);
  }

  /** Blocos ``` viram pre/code; o resto vira parágrafo. Tudo por textContent. */
  function renderRichText(container, text) {
    var last = 0;
    var match;
    FENCE.lastIndex = 0;
    while ((match = FENCE.exec(text)) !== null) {
      if (match.index > last) appendParagraph(container, text.slice(last, match.index));
      appendCode(container, match[1].trim(), match[2]);
      last = match.index + match[0].length;
      if (match[0].length === 0) FENCE.lastIndex++;
    }
    if (last < text.length) appendParagraph(container, text.slice(last));
  }

  function citationLabel(citation) {
    var language = citation.linguagem ? String(citation.linguagem) : '';
    var internal = typeof citation.documento === 'string' && citation.documento.indexOf('interno/') === 0;
    var who = internal ? language : capitalize(language);
    if (citation.versao) who += ' ' + citation.versao;
    return who.trim();
  }

  function renderCitations(container, citations) {
    clear(container);
    if (!citations || !citations.length) return;
    container.appendChild(h('h4', { text: 'Fontes' }));
    var list = h('ol', { cls: 'citations' });
    citations.forEach(function (citation) {
      var item = h('li', { attrs: { value: String(citation.n) } });
      item.appendChild(h('strong', { text: '[' + citation.n + '] ' + citationLabel(citation) }));
      if (citation.secao) item.appendChild(document.createTextNode(' · ' + citation.secao));
      if (citation.revisado === false) item.appendChild(h('span', { cls: 'badge warn', text: 'não revisado' }));
      list.appendChild(item);
    });
    container.appendChild(list);
  }

  function definition(list, term, value) {
    if (value === undefined || value === null || value === '') return;
    list.appendChild(h('dt', { text: term }));
    list.appendChild(h('dd', { text: value }));
  }

  function renderDiagnostics(container, meta, extra) {
    clear(container);
    if (!meta) return;
    var list = h('dl', { cls: 'diag' });
    definition(list, 'Rota', meta.rota === 'CACHE' ? 'CACHE (resposta recente repetida, sem busca nem modelo)' : meta.rota);
    definition(list, 'Modelo', meta.modelo);
    definition(list, 'Versão usada', meta.versaoUsada);
    if (typeof meta.scoreMaximo === 'number') {
      list.appendChild(h('dt', { text: 'Score do melhor trecho' }));
      var dd = h('dd');
      dd.appendChild(
        h('meter', { attrs: { min: '0', max: '1', value: String(meta.scoreMaximo), 'aria-label': 'Score' } })
      );
      dd.appendChild(document.createTextNode(' ' + meta.scoreMaximo.toFixed(2)));
      list.appendChild(dd);
    }
    if (meta.fontesNaoRevisadas !== undefined && meta.fontesNaoRevisadas !== null) {
      definition(list, 'Fonte não revisada', meta.fontesNaoRevisadas ? 'sim' : 'não');
    }
    if (typeof meta.frasesDescartadas === 'number') {
      definition(list, 'Frases descartadas', String(meta.frasesDescartadas));
    }
    if (meta.temposMs && typeof meta.temposMs === 'object') {
      definition(
        list,
        'Tempos',
        Object.keys(meta.temposMs)
          .map(function (stage) {
            return stage + ' ' + meta.temposMs[stage] + ' ms';
          })
          .join(' · ')
      );
    }
    definition(list, 'Conversa', meta.conversaId);
    definition(list, 'No navegador', extra);
    container.appendChild(list);
    if (meta.citacoes && meta.citacoes.length) {
      container.appendChild(h('h4', { text: 'Trechos enviados ao modelo' }));
      var docs = h('ul', { cls: 'doc-list' });
      meta.citacoes.forEach(function (citation) {
        var label = '[' + citation.n + '] ' + (citation.documento || '') + (citation.secao ? ' · ' + citation.secao : '');
        docs.appendChild(h('li', { text: label }));
      });
      container.appendChild(docs);
    }
  }

  function createAnswerView(question) {
    var article = h('article', { cls: 'answer' });
    article.appendChild(h('p', { cls: 'question', text: question }));
    var banner = h('div', { cls: 'version-banner', attrs: { role: 'note' } });
    banner.hidden = true;
    var body = h('div', { cls: 'answer-body', attrs: { 'aria-live': 'off', 'aria-busy': 'true' } });
    var failure = h('div');
    var cites = h('div', { cls: 'sources' });
    var details = h('details', { cls: 'diagnostics' });
    details.open = true;
    details.hidden = true;
    details.appendChild(h('summary', { text: 'Diagnóstico' }));
    var diag = h('div');
    details.appendChild(diag);
    [banner, body, failure, cites, details].forEach(function (part) {
      article.appendChild(part);
    });
    answers.appendChild(article);
    article.scrollIntoView({ block: 'nearest' });
    return { banner: banner, body: body, cites: cites, diag: diag, details: details, failure: failure };
  }

  function addBannerLine(view, text) {
    view.banner.hidden = false;
    view.banner.appendChild(h('p', { text: text }));
  }

  function sourceLine(source) {
    var name = source.nome || source.linguagem || '';
    var tail = source.versao ? ' ' + source.versao : source.versaoNaoInformada ? ' (versão não informada)' : '';
    return 'Documentação consultada: ' + (name + tail).trim();
  }

  /**
   * No done, a lista estruturada documentacaoConsultada é a fonte principal do banner (mesmo vazia: vazio quer dizer
   * que nenhuma frase aprovada citou fonte). Só um servidor sem esse campo cai nas linhas de texto e, por fim, nas
   * citações do metadata.
   */
  function applyFinalBanner(view, state, payload) {
    if (Array.isArray(payload.documentacaoConsultada)) {
      clear(view.banner);
      payload.documentacaoConsultada.forEach(function (source) {
        view.banner.appendChild(h('p', { text: sourceLine(source) }));
      });
      view.banner.hidden = !view.banner.firstChild;
      if (view.banner.firstChild) state.versionLine = true;
      return;
    }
    if (!state.versionLine) {
      var line = versionLineFromMeta(payload);
      if (line) addBannerLine(view, line);
    }
  }

  var NOTICE = /^\s*(Não tenho a documentação|Comparei só as versões|Considerei a versão|Não encontrei|A documentação .* não menciona)/;

  /** Plano B: se o servidor não mandou a linha "Documentação consultada", monta uma com os campos do metadata. */
  function versionLineFromMeta(meta) {
    var seen = [];
    (meta.citacoes || []).forEach(function (citation) {
      var label = citationLabel(citation);
      if (citation.versao && seen.indexOf(label) < 0) seen.push(label);
    });
    if (seen.length) return 'Documentação consultada: ' + seen.join(', ');
    if (meta.versaoUsada) return 'Documentação consultada: versão ' + meta.versaoUsada;
    return '';
  }

  function handleEvent(view, state, name, raw) {
    var payload;
    try {
      payload = JSON.parse(raw);
    } catch (e) {
      return;
    }
    if (!payload || typeof payload !== 'object') return;
    if (name === 'metadata' || name === 'done') {
      state.meta = payload;
      state.done = state.done || name === 'done';
      if (payload.conversaId) conversationId = payload.conversaId;
      renderCitations(view.cites, payload.citacoes);
      renderDiagnostics(view.diag, payload);
      view.details.hidden = false;
      if (name === 'done') applyFinalBanner(view, state, payload);
    } else if (name === 'text') {
      if (typeof payload.texto !== 'string') return;
      if (VERSION_PREFIX.test(payload.texto)) {
        state.versionLine = true;
        addBannerLine(view, payload.texto.trim());
      } else if (NOTICE.test(payload.texto)) {
        view.body.appendChild(h('p', { cls: 'notice', text: payload.texto.trim() }));
      } else {
        renderRichText(view.body, payload.texto);
      }
    } else if (name === 'error') {
      state.failed = true;
      showNote(
        view.failure,
        'error',
        typeof payload.mensagem === 'string' ? payload.mensagem : 'Não foi possível concluir a resposta.'
      );
    }
  }

  function readStream(response, view, state) {
    var parser = createSseParser(function (name, data) {
      handleEvent(view, state, name, data);
    });
    var decoder = new TextDecoder('utf-8');
    var reader = response.body.getReader();
    function pump() {
      return reader.read().then(function (result) {
        if (result.done) {
          parser.push(decoder.decode());
          parser.finish();
          return undefined;
        }
        parser.push(decoder.decode(result.value, { stream: true }));
        return pump();
      });
    }
    return pump();
  }

  askForm.addEventListener('submit', function (event) {
    event.preventDefault();
    var question = questionInput.value.trim();
    if (!question || controller) return;
    var view = createAnswerView(question);
    var state = { meta: null, done: false, failed: false };
    var started = performance.now();
    controller = new AbortController();
    askStop.hidden = false;
    setBusy(askSend, true);
    askStatus.textContent = 'Consultando a documentação...';

    var body = { pergunta: question };
    if (conversationId) body.conversaId = conversationId;

    api('/api/perguntas', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify(body),
      signal: controller.signal
    })
      .then(function (response) {
        if (!response.ok) {
          return readProblem(response).then(function (problem) {
            showNote(view.failure, 'error', problem.message);
          });
        }
        questionInput.value = '';
        return readStream(response, view, state).then(function () {
          if (!state.done && !state.failed) {
            showNote(view.failure, 'error', 'A conexão terminou antes do fim da resposta.');
          }
        });
      })
      .catch(function (error) {
        showNote(view.failure, 'error', errorText(error));
      })
      .then(function () {
        view.body.setAttribute('aria-busy', 'false');
        if (state.meta) renderDiagnostics(view.diag, state.meta, seconds(performance.now() - started));
        controller = null;
        askStop.hidden = true;
        setBusy(askSend, false);
        askStatus.textContent = state.done && !state.failed ? 'Resposta concluída.' : '';
        questionInput.focus();
      });
  });

  questionInput.addEventListener('keydown', function (event) {
    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      if (askForm.requestSubmit) askForm.requestSubmit();
    }
  });

  askStop.addEventListener('click', function () {
    if (controller) controller.abort();
  });

  $('ask-new').addEventListener('click', function () {
    conversationId = '';
    clear(answers);
    askStatus.textContent = 'Nova conversa.';
    questionInput.focus();
  });

  // ---------- tabela genérica ----------

  function buildTable(headers, rows) {
    var table = h('table');
    var head = h('tr');
    headers.forEach(function (title) {
      head.appendChild(h('th', { text: title, attrs: { scope: 'col' } }));
    });
    table.appendChild(h('thead', {}, [head]));
    var bodyNode = h('tbody');
    rows.forEach(function (cells) {
      var tr = h('tr');
      cells.forEach(function (cell) {
        var td = h('td');
        if (cell instanceof Node) td.appendChild(cell);
        else td.textContent = cell === undefined || cell === null ? '' : String(cell);
        tr.appendChild(td);
      });
      bodyNode.appendChild(tr);
    });
    table.appendChild(bodyNode);
    return table;
  }

  // ---------- Documentos ----------

  var docsData = [];
  var docsStatus = $('docs-status');
  var docsWrap = $('docs-table-wrap');

  function docMatches(doc, term) {
    if (!term) return true;
    var haystack = [doc.id, doc.fonte, doc.linguagem, doc.versao, doc.secao].join(' ').toLowerCase();
    return haystack.indexOf(term.toLowerCase()) >= 0;
  }

  function renderDocs() {
    clear(docsWrap);
    var term = $('docs-filter').value.trim();
    var shown = docsData.filter(function (doc) {
      return docMatches(doc, term);
    });
    docsStatus.textContent = shown.length + ' de ' + docsData.length + ' documentos';
    if (!shown.length) {
      docsWrap.appendChild(
        h('p', { cls: 'muted', text: docsData.length ? 'Nenhum documento bate com o filtro.' : 'O acervo está vazio.' })
      );
      return;
    }
    var rows = shown.map(function (doc) {
      var approve = h('button', {
        cls: 'small secondary',
        text: doc.revisado ? 'Desaprovar' : 'Aprovar',
        attrs: { type: 'button', 'aria-label': (doc.revisado ? 'Desaprovar ' : 'Aprovar ') + doc.id }
      });
      approve.addEventListener('click', function () {
        reviewDoc(doc, approve);
      });
      var remove = h('button', {
        cls: 'small danger',
        text: 'Excluir',
        attrs: { type: 'button', 'aria-label': 'Excluir ' + doc.id }
      });
      remove.addEventListener('click', function () {
        deleteDoc(doc, remove);
      });
      var state = doc.revisado
        ? h('span', { cls: 'badge ok', text: 'revisado' })
        : h('span', { cls: 'badge warn', text: 'não revisado' });
      return [
        doc.id,
        doc.fonte,
        doc.linguagem,
        doc.versao || '',
        doc.secao,
        state,
        doc.coletado_em || '',
        h('div', { cls: 'actions' }, [approve, remove])
      ];
    });
    docsWrap.appendChild(
      buildTable(['Id', 'Fonte', 'Linguagem', 'Versão', 'Seção', 'Estado', 'Coletado em', 'Ações'], rows)
    );
  }

  function loadDocs() {
    docsStatus.textContent = 'Carregando...';
    api('/api/documentos')
      .then(function (response) {
        if (!response.ok) {
          return readProblem(response).then(function (problem) {
            docsStatus.textContent = '';
            showNote(docsWrap, 'error', problem.message);
          });
        }
        return response.json().then(function (list) {
          docsLoaded = true;
          docsData = Array.isArray(list) ? list : [];
          renderDocs();
        });
      })
      .catch(function (error) {
        docsStatus.textContent = errorText(error);
      });
  }

  function reviewDoc(doc, button) {
    setBusy(button, true);
    api('/api/documentos?id=' + encodeURIComponent(doc.id), {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ revisado: !doc.revisado })
    })
      .then(function (response) {
        if (!response.ok) {
          return readProblem(response).then(function (problem) {
            docsStatus.textContent = problem.message;
            setBusy(button, false);
          });
        }
        doc.revisado = !doc.revisado;
        renderDocs();
        return undefined;
      })
      .catch(function (error) {
        docsStatus.textContent = errorText(error);
        setBusy(button, false);
      });
  }

  function deleteDoc(doc, button) {
    if (!window.confirm('Excluir ' + doc.id + '? Isso não pode ser desfeito.')) return;
    setBusy(button, true);
    api('/api/documentos?id=' + encodeURIComponent(doc.id), { method: 'DELETE' })
      .then(function (response) {
        if (!response.ok) {
          return readProblem(response).then(function (problem) {
            docsStatus.textContent = problem.message;
            setBusy(button, false);
          });
        }
        docsData = docsData.filter(function (other) {
          return other !== doc;
        });
        renderDocs();
        return undefined;
      })
      .catch(function (error) {
        docsStatus.textContent = errorText(error);
        setBusy(button, false);
      });
  }

  $('docs-refresh').addEventListener('click', loadDocs);
  $('docs-filter').addEventListener('input', renderDocs);

  var upForm = $('upload-form');
  var upResult = $('up-result');
  var upStatus = $('up-status');

  function appendField(data, name, id) {
    var value = $(id).value.trim();
    if (value) data.append(name, value);
  }

  function renderUploadSuccess(body) {
    clear(upResult);
    var box = h('div', { cls: 'note ok', attrs: { role: 'status' } });
    var ids = Array.isArray(body.ids) ? body.ids : [];
    box.appendChild(h('p', { text: ids.length === 1 ? '1 documento gravado:' : ids.length + ' documentos gravados:' }));
    var list = h('ul');
    ids.forEach(function (id) {
      list.appendChild(h('li', { text: id }));
    });
    box.appendChild(list);
    if (Array.isArray(body.substituidos) && body.substituidos.length) {
      box.appendChild(h('p', { text: 'Substituídos: ' + body.substituidos.join(', ') }));
    }
    box.appendChild(h('p', { cls: 'muted', text: 'Documentos novos entram como não revisados. Aprove na lista abaixo.' }));
    upResult.appendChild(box);
  }

  function renderUploadFailure(problem) {
    clear(upResult);
    var box = h('div', { cls: 'note error', attrs: { role: 'alert' } });
    box.appendChild(h('p', { text: problem.message }));
    var findings = problem.problem && problem.problem.segredos;
    if (Array.isArray(findings) && findings.length) {
      var list = h('ul');
      findings.forEach(function (finding) {
        list.appendChild(h('li', { text: String(finding.tipo) + ' na linha ' + String(finding.linha) }));
      });
      box.appendChild(list);
    }
    if (problem.problem && problem.problem.arquivo) {
      box.appendChild(h('p', { text: 'Em: ' + problem.problem.arquivo }));
    }
    if (problem.status === 409) {
      var retry = h('button', { text: 'Substituir e enviar de novo', attrs: { type: 'button' } });
      retry.addEventListener('click', function () {
        $('up-substituir').checked = true;
        if (upForm.requestSubmit) upForm.requestSubmit();
      });
      box.appendChild(retry);
    }
    upResult.appendChild(box);
  }

  upForm.addEventListener('submit', function (event) {
    event.preventDefault();
    var file = $('up-file').files[0];
    if (!file) return;
    var data = new FormData();
    data.append('file', file);
    appendField(data, 'fonte', 'up-fonte');
    appendField(data, 'linguagem', 'up-linguagem');
    appendField(data, 'versao', 'up-versao');
    appendField(data, 'secao', 'up-secao');
    appendField(data, 'url_origem', 'up-url');
    if ($('up-substituir').checked) data.append('substituir', 'true');
    var submit = upForm.querySelector('button[type="submit"]');
    setBusy(submit, true);
    upStatus.textContent = 'Enviando...';
    clear(upResult);
    api('/api/documentos', { method: 'POST', body: data })
      .then(function (response) {
        if (!response.ok) {
          return readProblem(response).then(renderUploadFailure);
        }
        return response.json().then(function (body) {
          renderUploadSuccess(body);
          upForm.reset();
          loadDocs();
        });
      })
      .catch(function (error) {
        renderUploadFailure({ status: 0, message: errorText(error), problem: {} });
      })
      .then(function () {
        upStatus.textContent = '';
        setBusy(submit, false);
      });
  });

  // ---------- Lacunas ----------

  var gapsStatus = $('gaps-status');
  var gapsWrap = $('gaps-table-wrap');

  function pick(object, names) {
    for (var i = 0; i < names.length; i++) {
      if (object[names[i]] !== undefined && object[names[i]] !== null) return object[names[i]];
    }
    return '';
  }

  function formatWhen(value) {
    if (!value) return '';
    var date = new Date(value);
    return isNaN(date.getTime()) ? String(value) : date.toLocaleString('pt-BR');
  }

  function unavailable(container, statusNode, what) {
    statusNode.textContent = '';
    showNote(container, 'info', what + ' ainda não está disponível nesta versão do servidor.');
  }

  function loadGaps() {
    gapsStatus.textContent = 'Carregando...';
    api('/api/lacunas?limite=100')
      .then(function (response) {
        if (response.status === 404) {
          unavailable(gapsWrap, gapsStatus, 'A lista de lacunas');
          return undefined;
        }
        if (!response.ok) {
          return readProblem(response).then(function (problem) {
            gapsStatus.textContent = '';
            showNote(gapsWrap, 'error', problem.message);
          });
        }
        return response.json().then(function (list) {
          gapsLoaded = true;
          clear(gapsWrap);
          var items = Array.isArray(list) ? list : [];
          gapsStatus.textContent = items.length + (items.length === 1 ? ' lacuna' : ' lacunas');
          if (!items.length) {
            gapsWrap.appendChild(h('p', { cls: 'muted', text: 'Nenhuma lacuna registrada.' }));
            return;
          }
          var rows = items.map(function (item) {
            return [
              pick(item, ['pergunta', 'question']),
              pick(item, ['vezes', 'times', 'quantidade']),
              formatWhen(pick(item, ['ultimaVez', 'ultimaPergunta', 'ultimaPerguntaEm', 'lastAskedAt']))
            ];
          });
          gapsWrap.appendChild(buildTable(['Pergunta', 'Vezes', 'Última vez'], rows));
        });
      })
      .catch(function (error) {
        gapsStatus.textContent = errorText(error);
      });
  }

  $('gaps-refresh').addEventListener('click', loadGaps);

  // ---------- Importar ----------

  var importForm = $('import-form');
  var importResult = $('im-result');
  var importStatus = $('im-status');
  var pollTimer = null;
  var currentJob = '';

  function stopPolling() {
    currentJob = '';
    if (pollTimer !== null) {
      window.clearTimeout(pollTimer);
      pollTimer = null;
    }
  }

  function renderJob(job) {
    clear(importResult);
    var status = String(pick(job, ['status']));
    var kind = status === 'FAILED' ? 'error' : status === 'DONE' ? 'ok' : 'info';
    var box = h('div', { cls: 'note ' + kind, attrs: { role: 'status' } });
    var labels = { RUNNING: 'em andamento', DONE: 'concluída', FAILED: 'falhou' };
    box.appendChild(h('p', { text: 'Importação ' + (labels[status] || status) }));
    var list = h('dl', { cls: 'diag' });
    definition(list, 'Páginas importadas', String(pick(job, ['imported', 'importadas', 'importados'])));
    definition(list, 'Ignoradas', String(pick(job, ['skipped', 'ignoradas', 'ignorados'])));
    definition(list, 'Com falha', String(pick(job, ['failed', 'falhas', 'falhados'])));
    definition(list, 'Colisões', String(pick(job, ['colisoes', 'collisions'])));
    box.appendChild(list);
    importResult.appendChild(box);
    return status;
  }

  function poll(id, attempt) {
    api('/api/importar/' + encodeURIComponent(id))
      .then(function (response) {
        if (id !== currentJob) return undefined;
        if (!response.ok) {
          return readProblem(response).then(function (problem) {
            if (id === currentJob) showNote(importResult, 'error', problem.message);
          });
        }
        return response.json().then(function (job) {
          if (id !== currentJob) return;
          if (renderJob(job) === 'RUNNING' && attempt < POLL_MAX) {
            pollTimer = window.setTimeout(function () {
              poll(id, attempt + 1);
            }, POLL_MS);
          }
        });
      })
      .catch(function (error) {
        if (id === currentJob) showNote(importResult, 'error', errorText(error));
      });
  }

  importForm.addEventListener('submit', function (event) {
    event.preventDefault();
    stopPolling();
    var submit = importForm.querySelector('button[type="submit"]');
    setBusy(submit, true);
    importStatus.textContent = 'Iniciando...';
    clear(importResult);
    var body = {
      url: $('im-url').value.trim(),
      linguagem: $('im-linguagem').value.trim(),
      versao: $('im-versao').value.trim(),
      profundidade: Number($('im-prof').value)
    };
    api('/api/importar', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    })
      .then(function (response) {
        if (response.status === 404) {
          unavailable(importResult, importStatus, 'A importação');
          return undefined;
        }
        if (!response.ok) {
          return readProblem(response).then(function (problem) {
            showNote(importResult, 'error', problem.message);
          });
        }
        return response.json().then(function (job) {
          if (!job || !job.id) {
            showNote(importResult, 'error', 'O servidor não devolveu o id da importação.');
            return;
          }
          showNote(importResult, 'info', 'Importação iniciada.');
          currentJob = String(job.id);
          poll(currentJob, 0);
        });
      })
      .catch(function (error) {
        showNote(importResult, 'error', errorText(error));
      })
      .then(function () {
        importStatus.textContent = '';
        setBusy(submit, false);
      });
  });
})();
