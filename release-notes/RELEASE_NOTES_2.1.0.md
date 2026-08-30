# Crux v2.1.0 - Release Notes

Spaces are the app now. Crux opens on them, one button takes you from a space into a session
on it, and a space can start from one of your GitHub repositories.

## Spaces are the front page

The app opens on **Crux**, the list of hosted spaces, rather than the hand-added server list.
Connecting a space goes straight into its sessions — it used to drop you on the server list to
find and tap the thing you had just connected.

The hand-added server list has not gone anywhere. It lives under **Settings → Local servers**,
for an opencode you run yourself.

## Start a space from a repository

Pick one of your GitHub repositories when creating a space and it is checked out on first
connect, becoming where that space's sessions start. Private repositories work: Crux holds a
GitHub token for the clone and strips it from the remote immediately afterwards, so nothing
long-lived is left in `.git/config` inside a container built to run shell commands.

The repository picker filters as you type, marks private repositories, and reads several pages
of your account rather than the first hundred.

## GitHub Codespaces

A space can now be a **GitHub Codespace**, alongside Hugging Face and Railway. It is created on
your own account with your own token and billed to your quota — Crux hosts and pays for nothing,
the same as the other two.

Two things are unlike them. The forwarded port stays private, reached with your own GitHub token
rather than exposed to the internet, so it is protected by two independent layers instead of
Basic Auth alone. And a codespace stops itself when idle; asking to connect starts it again, and
the app waits out the resume rather than reporting a dead server.

## GitHub is how you sign in

GitHub is now the only way to create a Crux account. Hugging Face and Railway are connected to
an account that already exists, from **Settings → Accounts**.

This is a rule about creating accounts, not about signing in: an identity already attached to an
account still signs into it, so nobody who linked Hugging Face before this is shut out of their
own spaces.

Crux moved from a GitHub App to an OAuth App to make this possible — GitHub Apps cannot call any
Codespaces endpoint, at any permission level. **Existing users must sign in again**, and the
consent screen is broader than before: an OAuth App's only key to a private repository is the
`repo` scope, which covers every repository you own. There is no narrower one.

## Creating a space

The form asks for a repository and little else. Name, template, workspace and password moved
behind **Advanced**, all with working defaults; the name follows from the repository you chose,
made legal and made unique. The provider dropdown always shows, so Railway and Hugging Face are
visible as options — picking one you have not connected offers to connect it.

## Smaller things

- Space cards are the button: tap one to open it. Delete and retry moved into an overflow, with
  the address available to copy. Status carries a shape as well as a colour, and the provider is
  shown by its own mark.
- Loading draws placeholders rather than a spinner, so the list stops jumping.
- Toasts on signing in, signing out, connecting an account, and deleting a space.
- The account list keeps a fixed order, GitHub first, and no longer rearranges as accounts come
  and go.
