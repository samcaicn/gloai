import React from 'react';

/** The send button's arrow, drawn in the button's text colour: the conversation's composer and the home one. */
const SendArrowIcon: React.FC<{ size?: number }> = ({ size = 16 }) => (
  <svg width={size} height={size} viewBox='0 0 24 24' fill='none' stroke='currentColor' aria-hidden='true'>
    <path d='M12 19V5' strokeWidth='2.7' strokeLinecap='round' />
    <path d='M6.5 10.5 12 5l5.5 5.5' strokeWidth='2.7' strokeLinecap='round' strokeLinejoin='round' />
  </svg>
);

export default SendArrowIcon;
