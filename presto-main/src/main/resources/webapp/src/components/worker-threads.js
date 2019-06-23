/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import React from "react";

import {
    getFirstParameter
} from "../utils";

const ALL_THREADS = "All Threads";
const QUERY_THREADS = "Running Queries";

const ALL_THREAD_STATE = "ALL";
const THREAD_STATES = [ALL_THREAD_STATE, "RUNNABLE", "BLOCKED", "WAITING", "TIMED_WAITING", "NEW", "TERMINATED"];

export class WorkerThreads extends React.Component {
    constructor(props) {
        super(props);
        this.state = {
            serverInfo: null,
            initialized: false,
            ended: false,

            threads: null,

            snapshotTime: null,

            selectedGroup: ALL_THREADS,
            selectedThreadState: ALL_THREAD_STATE,
        };
    }

    componentDidMount() {
        const nodeId = getFirstParameter(window.location.search);
        $.get('/v1/worker/' + nodeId + '/thread', function (threads) {
            this.setState({
                threads: this.processThreads(threads),
                snapshotTime: new Date(),
                initialized: true,
            });
        }.bind(this))
            .error(function () {
                this.setState({
                    initialized: true,
                });
            }.bind(this));
    }

    componentDidUpdate() {
        new Clipboard('.copy-button');
    }

    spliceThreadsByRegex(threads, regex) {
        return [threads.filter(t => t.name.match(regex) !== null), threads.filter(t => t.name.match(regex) === null)];
    }

    processThreads(threads) {
        const result = {};

        result[ALL_THREADS] = threads;

        // first, pull out threads that are running queries
        let [matched, remaining] = this.spliceThreadsByRegex(threads, /([0-9])*_([0-9])*_([0-9])*_.*?\.([0-9])*\.([0-9])*-([0-9])*-([0-9])*/);
        result[QUERY_THREADS] = matched;

        if (matched.length !== 0) {
            this.setState({
                selectedGroup: QUERY_THREADS
            })
        }

        while (remaining.length > 0) {
            const match = /(.*?)-[0-9]+/.exec(remaining[0].name);

            if (match === null) {
                [result[remaining[0].name], remaining] = this.spliceThreadsByRegex(remaining, remaining[0].name);
            }
            else {
                const [, namePrefix, ...ignored] = match;
                [result[namePrefix], remaining] = this.spliceThreadsByRegex(remaining, namePrefix + "-[0-9]+");
            }
        }

        return result
    }

    handleGroupClick(selectedGroup, event) {
        this.setState({
            selectedGroup: selectedGroup
        });
        event.preventDefault();
    }

    handleThreadStateClick(selectedThreadState, event) {
        this.setState({
            selectedThreadState: selectedThreadState
        });
        event.preventDefault();
    }

    handleNewSnapshotClick(event) {
        this.setState({
            initialized: false
        });
        this.componentDidMount();
        event.preventDefault();
    }

    filterThreads(group, state) {
        return this.state.threads[group].filter(t => t.state === state || state === ALL_THREAD_STATE);
    }

    renderGroupListItem(group) {
        return (
            <li key={group}>
                <a href="#" className={this.state.selectedGroup === group ? "selected" : ""} onClick={this.handleGroupClick.bind(this, group)}>
                    {group} ({this.filterThreads(group, this.state.selectedThreadState).length})
                </a>
            </li>
        );
    }

    renderThreadStateListItem(threadState) {
        return (
            <li key={threadState}>
                <a href="#" className={this.state.selectedThreadState === threadState ? "selected" : ""} onClick={this.handleThreadStateClick.bind(this, threadState)}>
                    {threadState} ({this.filterThreads(this.state.selectedGroup, threadState).length})
                </a>
            </li>
        );
    }

    renderStackLine(threadId) {
        return (stackLine, index) => {
            return (
                <div key={threadId + index}>
                    &nbsp;&nbsp;at {stackLine.className}.{stackLine.method}
                    (<span className="font-light">{stackLine.file}:{stackLine.line}</span>)
                </div>);
        };
    }

    renderThread(threadInfo) {
        return (
            <div key={threadInfo.id}>
                <span className="font-white">{threadInfo.name} {threadInfo.state} #{threadInfo.id} {threadInfo.lockOwnerId}</span>
                <a className="copy-button" data-clipboard-target={"#stack-trace-" + threadInfo.id} data-toggle="tooltip" data-placement="right"
                   title="Copy to clipboard">
                    <span className="glyphicon glyphicon-copy" alt="Copy to clipboard"/>
                </a>
                <br/>
                <span className="stack-traces" id={"stack-trace-" + threadInfo.id}>
                    {threadInfo.stackTrace.map(this.renderStackLine(threadInfo.id))}
                </span>
                <div>
                    &nbsp;
                </div>
            </div>
        );
    }

    render() {
        const threads = this.state.threads;

        if (threads === null) {
            if (this.state.initialized === false) {
                return (
                    <div className="loader">Loading...</div>
                );
            }
            else {
                return (
                    <div className="row error-message">
                        <div className="col-xs-12"><h4>Thread snapshot could not be loaded</h4></div>
                    </div>
                );
            }
        }

        const filteredThreads = this.filterThreads(this.state.selectedGroup, this.state.selectedThreadState);
        let renderedThreads;
        if (filteredThreads.length === 0 && this.state.selectedThreadState === ALL_THREAD_STATE) {
            renderedThreads = (
                <div className="row error-message">
                    <div className="col-xs-12"><h4>No threads in group '{this.state.selectedGroup}'</h4></div>
                </div>);
        }
        else if (filteredThreads.length === 0 && this.state.selectedGroup === ALL_THREADS) {
            renderedThreads = (
                <div className="row error-message">
                    <div className="col-xs-12"><h4>No threads with state {this.state.selectedThreadState}</h4></div>
                </div>);
        }
        else if (filteredThreads.length === 0) {
            renderedThreads = (
                <div className="row error-message">
                    <div className="col-xs-12"><h4>No threads in group '{this.state.selectedGroup}' with state {this.state.selectedThreadState}</h4></div>
                </div>);
        }
        else {
            renderedThreads = (
                <pre>
                    {filteredThreads.map(t => this.renderThread(t))}
                </pre>);
        }

        return (
            <div>
                <div className="row">
                    <div className="col-xs-3">
                        <h3>
                            Thread Snapshot
                            <a className="btn copy-button" data-clipboard-target="#stack-traces" data-toggle="tooltip" data-placement="right" title="Copy to clipboard">
                                <span className="glyphicon glyphicon-copy" alt="Copy to clipboard"/>
                            </a>
                            &nbsp;
                        </h3>
                    </div>
                    <div className="col-xs-9">
                        <table className="header-inline-links">
                            <tbody>
                            <tr>
                                <td>
                                    <small>Snapshot at {this.state.snapshotTime.toTimeString()}</small>
                                    &nbsp;&nbsp;
                                </td>
                                <td>
                                    <button className="btn btn-info live-button" onClick={this.handleNewSnapshotClick.bind(this)}>New Snapshot</button>
                                    &nbsp;&nbsp;
                                    &nbsp;&nbsp;
                                </td>
                                <td>
                                    <div className="input-group-btn text-right">
                                        <button type="button" className="btn btn-default dropdown-toggle pull-right text-right" data-toggle="dropdown" aria-haspopup="true"
                                                aria-expanded="false">
                                            <strong>Group:</strong> {this.state.selectedGroup} <span className="caret"/>
                                        </button>
                                        <ul className="dropdown-menu">
                                            {Object.keys(threads).map(group => this.renderGroupListItem(group))}
                                        </ul>
                                    </div>
                                </td>
                                <td>
                                    <div className="input-group-btn text-right">
                                        <button type="button" className="btn btn-default dropdown-toggle pull-right text-right" data-toggle="dropdown" aria-haspopup="true"
                                                aria-expanded="false">
                                            <strong>State:</strong> {this.state.selectedThreadState} <span className="caret"/>
                                        </button>
                                        <ul className="dropdown-menu">
                                            {THREAD_STATES.map(state => this.renderThreadStateListItem(state))}
                                        </ul>
                                    </div>
                                </td>
                            </tr>
                            </tbody>
                        </table>
                    </div>
                </div>
                <div className="row">
                    <div className="col-xs-12">
                        <hr className="h3-hr"/>
                        <div id="stack-traces">
                            {renderedThreads}
                        </div>
                    </div>
                </div>
            </div>
        );
    }
}
