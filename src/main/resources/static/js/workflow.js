/*
 * 워크플로우 화면(스텝1/2/3 · 종료) 공통 클라이언트.
 *
 * 스텝 화면 셋이 "그 단계에 머물러 있는 내 태스크 목록 + 완료 버튼" 이라는 같은 구조라서
 * jqGrid 구성과 완료 처리를 여기 한 곳에 둔다. 각 JSP 는 단계 키만 넘긴다.
 *
 *   workflow.initTaskGrid({ step: 'step1', gridId: '#workflowGrid', pagerId: '#workflowGridPager' });
 *   workflow.bindCreateButton('#btnCreateTask', { nameInputId: '#newTaskName' });   // 스텝1 전용
 *   workflow.initFinishedGrid({ ... });               // 종료 화면(ends.jsp)
 *
 * jQuery / jqGrid / csrf.js / dialog.js 보다 뒤에 로드할 것.
 * (CSRF 토큰은 csrf.js 의 $.ajaxSend 가 자동으로 실어 주므로 여기서 다루지 않는다.)
 *
 * 알림·확인은 브라우저 기본 alert()/confirm() 이 아니라 appDialog(레이어 팝업)를 쓴다.
 * 기본 대화상자와 달리 스크립트를 멈추지 않으므로, 확인 결과는 콜백으로 이어받는다.
 */
(function (global) {
    'use strict';

    let BASE_URL = '/user/workflow';

    /* 날짜는 서버가 "yyyy-MM-dd HH:mm:ss" 문자열로 내려 주므로 화면 변환은 없다.
       (다른 목록과 달리 Date 직렬화 포맷에 의존하지 않기 위한 계약)
       태스크 이름은 사용자 입력이라 그대로 innerHTML 에 들어가지 않도록 이스케이프한다. */
    function textFormatter(cellValue) {
        if (!cellValue) return '';
        return String(cellValue)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function completeButtonFormatter(cellValue, options) {
        return '<button type="button" class="search-form__btn btn-complete" data-id="'
            + options.rowId + '">완료</button>';
    }

    /** 공통 jqGrid 옵션 — 컬럼 구성만 화면별로 갈린다. */
    function baseGridOptions(opts) {
        return {
            datatype:     'json',
            mtype:        'GET',
            pager:        opts.pagerId,
            rownumbers:   true,
            rownumWidth:  56,
            rowNum:       10,
            rowList:      [10, 20, 50, 100],
            viewrecords:  true,
            autowidth:    true,
            height:       opts.height || '360',
            emptyrecords: '조회된 데이터가 없습니다.',
            loadError: function (xhr, status, error) {
                appDialog.warn('목록 조회 중 오류가 발생했습니다.\n' + status + ' : ' + error);
            }
        };
    }

    /**
     * 해당 단계에 머물러 있는 태스크 목록 그리드.
     *
     * @param opts.step     step1 | step2 | step3
     * @param opts.gridId   그리드 table 셀렉터
     * @param opts.pagerId  페이저 div 셀렉터
     * @param opts.caption  그리드 캡션
     */
    function initTaskGrid(opts) {
        let $grid = $(opts.gridId);

        $grid.jqGrid($.extend(baseGridOptions(opts), {
            url:      BASE_URL + '/tasks',
            postData: { step: opts.step },
            caption:  opts.caption || '진행 중 태스크',
            colNames: ['엔진 태스크 ID', '태스크 ID', '태스크이름', '현재 단계', '담당자', '생성일시', '완료'],
            colModel: [
                /* 완료 요청에 쓰는 엔진 내부 id — rowId 로만 쓰고 화면에는 감춘다. */
                { name: 'flowableTaskId', width: 120, hidden: true },
                { name: 'taskId',         width: 210 },
                { name: 'taskName',       width: 240, formatter: textFormatter },
                { name: 'stepName',       width: 90,  align: 'center' },
                { name: 'assignee',       width: 110, align: 'center' },
                { name: 'createDate',     width: 160, align: 'center', formatter: textFormatter },
                { name: 'complete',       width: 80,  align: 'center', sortable: false, formatter: completeButtonFormatter }
            ],
            jsonReader: {
                root: 'rows', page: 'page', total: 'total', records: 'records',
                repeatitems: false, id: 'flowableTaskId'
            }
        }));

        /* 완료 버튼은 그리드가 다시 그려질 때마다 새로 만들어지므로 위임으로 묶는다. */
        $grid.on('click', '.btn-complete', function () {
            completeTask($(this).data('id'), opts.step, opts.gridId, opts.onCompleted);
        });

        return $grid;
    }

    /** 종료된 태스크 목록 그리드(읽기 전용). */
    function initFinishedGrid(opts) {
        return $(opts.gridId).jqGrid($.extend(baseGridOptions(opts), {
            url:     BASE_URL + '/finished',
            caption: opts.caption || '종료된 태스크',
            colNames: ['프로세스 ID', '태스크 ID', '태스크이름', '상태', '담당자', '생성일시', '종료일시'],
            colModel: [
                { name: 'processInstanceId', width: 120, hidden: true },
                { name: 'taskId',            width: 210 },
                { name: 'taskName',          width: 220, formatter: textFormatter },
                { name: 'stepName',          width: 80,  align: 'center' },
                { name: 'assignee',          width: 110, align: 'center' },
                { name: 'createDate',        width: 160, align: 'center', formatter: textFormatter },
                { name: 'stepDate',          width: 160, align: 'center', formatter: textFormatter }
            ],
            jsonReader: {
                root: 'rows', page: 'page', total: 'total', records: 'records',
                repeatitems: false, id: 'processInstanceId'
            }
        }));
    }

    /** 현재 단계 완료 → 다음 단계로 이동(스텝3 이면 종료). 확인을 받은 뒤 전송한다. */
    function completeTask(taskId, step, gridId, onCompleted) {
        if (!taskId) return;

        appDialog.confirm('현재 단계를 완료하고 다음 단계로 넘기시겠습니까?', {
            title:       '단계 완료',
            confirmText: '완료',
            onConfirm:   function () {
                sendComplete(taskId, step, gridId, onCompleted);
            }
        });
    }

    /** 완료 요청 전송 — completeTask 의 확인 단계를 지난 뒤에만 호출된다. */
    function sendComplete(taskId, step, gridId, onCompleted) {
        $.ajax({
            url:         BASE_URL + '/tasks/' + encodeURIComponent(taskId) + '/complete',
            type:        'POST',
            contentType: 'application/json; charset=UTF-8',
            /* step 을 함께 보내 이미 넘어간 태스크를 다시 완료하는 것을 서버가 막게 한다. */
            data:        JSON.stringify({ step: step }),
            dataType:    'json'
        }).done(function (res) {
            if (!res.success) {
                appDialog.warn(res.message || '단계 완료에 실패했습니다.');
                reloadGrid(gridId);
                return;
            }
            appDialog.alert(res.finished
                ? '태스크가 종료되었습니다.'
                : '완료되었습니다. 다음 단계: ' + res.nextStep, { title: '단계 완료' });

            reloadGrid(gridId);
            if (typeof onCompleted === 'function') {
                onCompleted(res);
            }
        }).fail(function (xhr, status, error) {
            appDialog.warn('단계 완료 중 오류가 발생했습니다.\n' + status + ' : ' + error);
        });
    }

    /**
     * "태스크생성" 버튼 배선 — 태스크 이름과 함께 보내면 task_master 에 업무 정보가 남고
     * 프로세스 인스턴스 한 건이 시작되어 스텝1 태스크가 생긴다.
     *
     * @param opts.nameInputId 태스크 이름 입력 셀렉터 (Enter 로도 생성된다)
     * @param opts.gridId      생성 후 다시 읽을 그리드
     */
    function bindCreateButton(buttonId, opts) {
        opts = opts || {};

        let $btn   = $(buttonId);
        let $input = opts.nameInputId ? $(opts.nameInputId) : null;

        function createTask() {
            let taskName = $input ? $.trim($input.val()) : '';

            /* 이름 없이 만들면 모든 화면에서 이름 칸이 빈 채로 남는다 — 여기서 막는다. */
            if (taskName === '') {
                appDialog.warn('태스크 이름을 입력하세요.', {
                    onClose: function () { if ($input) $input.trigger('focus'); }
                });
                return;
            }

            $btn.prop('disabled', true);   // 연타로 태스크가 여러 건 생기는 것을 막는다

            $.ajax({
                url:         BASE_URL + '/tasks',
                type:        'POST',
                contentType: 'application/json; charset=UTF-8',
                data:        JSON.stringify({ taskName: taskName }),
                dataType:    'json'
            }).done(function (res) {
                if (!res.success) {
                    appDialog.warn(res.message || '태스크 생성에 실패했습니다.');
                    return;
                }
                appDialog.alert(
                    '태스크가 생성되었습니다.\n태스크 ID: ' + res.taskId + '\n태스크이름: ' + res.taskName,
                    { title: '태스크 생성' });
                if ($input) $input.val('');
                reloadGrid(opts.gridId);
            }).fail(function (xhr, status, error) {
                appDialog.warn('태스크 생성 중 오류가 발생했습니다.\n' + status + ' : ' + error);
            }).always(function () {
                $btn.prop('disabled', false);
            });
        }

        $btn.on('click', createTask);

        if ($input) {
            $input.on('keydown', function (e) {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    createTask();
                }
            });
        }
    }

    function reloadGrid(gridId) {
        if (gridId) {
            $(gridId).jqGrid('setGridParam', { page: 1 }).trigger('reloadGrid');
        }
    }

    global.workflow = {
        initTaskGrid:     initTaskGrid,
        initFinishedGrid: initFinishedGrid,
        bindCreateButton: bindCreateButton,
        reloadGrid:       reloadGrid
    };
})(window);
