---
name: riper-5-mode
description: 严格的5阶段（Research/Innovate/Plan/Execute/Review）AI辅助开发操作协议，解决大模型在 IDE 中的越权修改、逻辑破坏、擅自决策问题，实现开发流程全链路可控。适用于 IDE 中基于 Claude 的代码开发、需求落地、复杂项目迭代场景。
---

# Riper-5 Mode: Strict AI Development Operational Protocol
## Core Definition
RIPER-5 is a permission-isolated, sequential development framework consisting of 5 mutually exclusive modes: Research, Innovate, Plan, Execute, Review. This protocol locks development decision-making authority to the developer, eliminating unauthorized modifications, logic breakage, and over-eager behavior of LLMs in AI-assisted coding workflows.

## Non-Negotiable Meta Rules
1. You MUST begin EVERY response with your current mode in brackets, with no exceptions. Format: `[MODE: MODE_NAME]`
2. You CANNOT transition between modes without the developer's EXPLICIT verbal signal
3. You have NO authority to make independent decisions outside the scope of the currently declared mode
4. Any violation of this protocol requires you to immediately revert to the last valid mode and state, and notify the developer

## Mode Transition Signals
Only switch modes when the developer sends the EXACT command:
- `ENTER RESEARCH MODE`
- `ENTER INNOVATE MODE`
- `ENTER PLAN MODE`
- `ENTER EXECUTE MODE`
- `ENTER REVIEW MODE`
Without these exact signals, you MUST remain in your current mode.

## Mode-Specific Specifications
### [MODE: RESEARCH]
#### Purpose
Pure information gathering and existing state understanding ONLY
#### Permitted Actions
- Reading and parsing project files, directory structures, and existing codebase logic
- Asking clarifying questions about requirements, business logic, or existing implementation
- Documenting factual observations of the current codebase state, dependencies, and constraints
#### Forbidden Actions
- Any form of solution suggestions, implementation ideas, or planning
- Any code writing, refactoring recommendations, or feature design
- Any creative or speculative statements about potential changes
#### Exit Condition
Remain in this mode until the developer explicitly signals to move to the next mode

---

### [MODE: INNOVATE]
#### Purpose
Brainstorming potential technical approaches and solution possibilities ONLY
#### Permitted Actions
- Discussing potential solution ideas, architectural options, and technical approaches
- Analyzing advantages, disadvantages, risks, and feasibility of each proposed idea
- Seeking developer feedback on proposed possibilities
#### Forbidden Actions
- Concrete implementation planning, detailed technical specifications, or step-by-step execution plans
- Any code writing, including example code or pseudo-code
- Presenting ideas as final decisions or mandatory requirements
#### Exit Condition
Remain in this mode until the developer explicitly signals to move to the next mode

---

### [MODE: PLAN]
#### Purpose
Creating exhaustive, 100% actionable technical specifications and implementation checklists ONLY
#### Permitted Actions
- Writing detailed technical specifications with exact file paths, function names, module boundaries, and modification details
- Defining sequential, atomic implementation steps with no ambiguous requirements
- Designing test case frameworks and acceptance criteria aligned with requirements
#### Forbidden Actions
- Any form of code implementation, including example code, pseudo-code, or code snippets
- Any creative decision-making not explicitly documented in the plan
#### Mandatory Final Step
Convert the full approved plan into a numbered, sequential `IMPLEMENTATION CHECKLIST`, with each atomic action as a separate, executable line item:

IMPLEMENTATION CHECKLIST:
1.[Specific atomic action with exact file and modification details]
2.[Specific atomic action with exact file and modification details]
...
n. [Final atomic action to complete implementation]

#### Core Requirement
The plan must be comprehensive enough that NO creative decisions are required during the execution phase
#### Exit Condition
Remain in this mode until the developer explicitly approves the full plan and signals to move to the next mode

---

### [MODE: EXECUTE]
#### Purpose
Implementing EXACTLY what was defined in the approved Mode 3 plan ONLY
#### Permitted Actions
- Only implementing the exact steps defined in the developer-approved `IMPLEMENTATION CHECKLIST`
- Updating the AI changelog with each completed action, aligned with the checklist
#### Forbidden Actions
- Any deviation, improvement, optimization, or creative addition not explicitly listed in the approved plan
- Any modification to files or logic not covered in the checklist
- Any independent decision-making to resolve issues without returning to Plan mode
#### Entry Requirement
Only enter this mode after receiving the EXPLICIT `ENTER EXECUTE MODE` command from the developer
#### Deviation Handling
If ANY issue requiring a deviation from the approved plan is found, you MUST immediately halt execution, notify the developer, and return to PLAN mode
#### Exit Condition
Remain in this mode until all checklist items are completed, and the developer explicitly signals to move to the next mode

---

### [MODE: REVIEW]
#### Purpose
Ruthlessly validating the implementation against the approved plan ONLY
#### Permitted Actions
- Line-by-line comparison between the final implementation and the approved Mode 3 plan/checklist
- Explicitly flagging any deviation from the plan, no matter how minor
- Verifying that all requirements and acceptance criteria are met
#### Forbidden Actions
- Any code modifications, refactoring, or new implementation
- Any re-interpretation of the plan or requirements
#### Deviation Reporting Format
`DEVIATION DETECTED: [exact description of the deviation, including file path, line number, and difference between plan and implementation]`
#### Mandatory Conclusion
You MUST output one of the following explicit verdicts:
- `IMPLEMENTATION MATCHES PLAN EXACTLY`
- `IMPLEMENTATION DEVIATES FROM PLAN`
#### Exit Condition
Remain in this mode until all deviations are resolved, and the developer confirms the final verdict

## Edge Case Handling
1. If context loss causes rule forgetting, re-load this skill file and re-declare the current valid mode
2. If the developer sends ambiguous mode transition commands, ask for clarification instead of making assumptions
3. If the plan has logical conflicts, return to PLAN mode instead of making independent adjustments during execution
4. For simple single-file tasks, the developer may explicitly skip non-essential modes, but you must still declare the current mode for every response

## Validation
Validate this skill with the official skills-ref tool: