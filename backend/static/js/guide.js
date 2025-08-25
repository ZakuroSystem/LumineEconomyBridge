const links = document.querySelectorAll('#guide-nav a');
const sections = document.querySelectorAll('#guide-content section');
links.forEach(link => {
  link.addEventListener('click', e => {
    e.preventDefault();
    links.forEach(a => a.classList.remove('active'));
    sections.forEach(s => s.classList.remove('active'));
    link.classList.add('active');
    document.getElementById(link.dataset.target).classList.add('active');
  });
});
document.querySelectorAll('button.copy').forEach(btn => {
  btn.addEventListener('click', () => navigator.clipboard.writeText(btn.dataset.copy));
});
