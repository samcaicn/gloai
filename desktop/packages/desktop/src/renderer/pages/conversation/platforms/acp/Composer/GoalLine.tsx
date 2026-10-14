/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

import { Button, Tooltip } from '@arco-design/web-react';
import React, { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { SendBoxCommandState } from '@/renderer/utils/emitter';
import type { GoalSnapshot } from './goalState';
import styles from './GoalLine.module.css';

/** How long a pressed 结束 waits for the goal to go before it can be pressed again. */
const END_WAIT_MS = 60_000;

/**
 * One quiet line above the send box while a goal runs or waits: 「目标进行中：<condition>」 and a plain link that ends
 * it. A goal is a command the person typed (`/goal <condition>`), not a mode of the send box, so this line is all the
 * conversation shows of it; the board and the judge's log follow its continuations. Give it `key` by the condition,
 * so a new goal starts with the link enabled.
 *
 * `onEnd` sends `/goal clear` and hears what became of it. Once pressed, the link waits: ending a running goal stops
 * its run first, which pauses it for a moment, and the line goes when the harness says the goal is cleared. It comes
 * back if the command was dropped, or if the goal is still there a minute later.
 */
const GoalLine: React.FC<{
  goal: GoalSnapshot;
  onEnd: (heard: (state: SendBoxCommandState) => void) => void;
}> = ({ goal, onEnd }) => {
  const { t } = useTranslation();
  const [ending, setEnding] = useState(false);
  useEffect(() => {
    if (!ending) return undefined;
    const timer = setTimeout(() => setEnding(false), END_WAIT_MS);
    return () => clearTimeout(timer);
  }, [ending]);
  const end = () => {
    setEnding(true);
    onEnd((state) => {
      if (state === 'dropped') setEnding(false);
    });
  };
  const paused = goal.status === 'paused';
  return (
    <div className={styles.line} role='status' data-testid='composer-goal-line' data-status={goal.status}>
      {/* The condition in full on hover; the line itself keeps to one row. */}
      <Tooltip content={goal.text} position='top'>
        <span className={styles.text} dir='auto' data-testid='composer-goal-text'>
          {t(paused ? 'conversation.composer.goalLine.paused' : 'conversation.composer.goalLine.running', {
            goal: goal.text,
          })}
        </span>
      </Tooltip>
      <Button
        type='text'
        size='mini'
        className={styles.end}
        disabled={ending}
        aria-label={t('conversation.composer.goalLine.endLabel')}
        data-testid='composer-goal-end'
        onClick={end}
      >
        {t('conversation.composer.goalLine.end')}
      </Button>
    </div>
  );
};

export default GoalLine;
