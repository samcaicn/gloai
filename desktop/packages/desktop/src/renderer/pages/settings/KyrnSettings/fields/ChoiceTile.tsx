import React from 'react';
import classNames from 'classnames';
import styles from '../sections/sections.module.css';

type ChoiceTileProps = {
  title: React.ReactNode;
  tag?: React.ReactNode;
  description?: React.ReactNode;
  active: boolean;
  onPick: () => void;
  testId?: string;
  /** Shown under the description while the tile is chosen: the one thing the choice needs. */
  children?: React.ReactNode;
};

/** One option of a radio group drawn as a tile: a name, a short tag, one sentence, and the chosen tile's fields. */
export default function ChoiceTile({ title, tag, description, active, onPick, testId, children }: ChoiceTileProps) {
  return (
    <div
      role='radio'
      aria-checked={active}
      tabIndex={0}
      data-testid={testId}
      className={classNames(styles.choice, active && styles.choiceActive)}
      onClick={() => !active && onPick()}
      onKeyDown={(event) => {
        // Keys typed into a field inside the tile are that field's.
        if (event.target !== event.currentTarget) return;
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          if (!active) onPick();
        }
      }}
    >
      <span className={styles.choiceRadio} aria-hidden='true' />
      <div className={styles.choiceText}>
        <div className={styles.choiceTitle}>
          {title}
          {tag ? <span className={styles.choiceTag}>{tag}</span> : null}
        </div>
        {description ? <div className={styles.choiceDescription}>{description}</div> : null}
        {active && children ? (
          // Clicks in the fields must not reach the tile.
          <div className={styles.choiceBody} onClick={(event) => event.stopPropagation()}>
            {children}
          </div>
        ) : null}
      </div>
    </div>
  );
}
